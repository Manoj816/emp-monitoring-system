package com.empverify.service;

import com.empverify.domain.Employee;
import com.empverify.repo.EmployeeRepository;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bulk-creates employees from a CSV file, so onboarding a department does not
 * mean filling the "Add employee" form once per person.
 *
 * <p>The file is taken as a whole: if any row is unusable, nothing is stored and
 * every bad row is reported at once, so a single round of corrections is enough.
 * A part-imported file is the worse outcome - the portal cannot show which half
 * arrived, and re-uploading the corrected file then collides with the records
 * that did.
 *
 * <p>A CSV holds text, so it cannot carry the ID/photo itself. A row names its
 * picture in the optional {@code photoFile} column and the pictures are chosen
 * alongside the file; the import pairs them up by name. The picture has to be a
 * JPEG or PNG, because that is what the face matcher reads - a PDF of an ID card
 * is not something a face can be compared against, whatever it looks like to a
 * person.
 *
 * <p>A row may leave {@code photoFile} empty, and then that employee arrives
 * with no reference. They are listed as "ID / photo: missing", counted by the
 * "Photo missing" tile, and cannot be verified until somebody uploads one - a
 * check needs a reference to compare the face against. Letting the photo come
 * later is the whole point of the bulk path; the single-employee form still
 * demands one, because there nothing is gained by deferring it.
 */
@Service
public class EmployeeImportService {

    /** Rows, not counting the header, that one upload may carry. */
    public static final int MAX_ROWS = 500;

    /** Bad rows listed individually; the report still counts the rest. */
    static final int MAX_PROBLEMS_LISTED = 50;

    /**
     * The departments an employee can belong to - the same fixed list the "Add
     * employee" form offers (admin-portal/src/api.ts, DEPARTMENTS; keep the two
     * in step). An import is not a way to invent a department, because the
     * assign-system popup filters by this list: a typo would create people no
     * filter ever finds. A value outside it is a rejected row, not a new
     * department. Matching ignores case and the stored value is the spelling
     * below, so "engineering" in the file is stored as "Engineering".
     */
    public static final List<String> DEPARTMENTS =
            List.of("Engineering", "Sales", "Human Resources", "Finance");

    /** Header names accepted for each field, so a hand-made file does not have to guess. */
    private static final Map<String, String> COLUMNS = Map.ofEntries(
            Map.entry("employeecode", "employeeCode"),
            Map.entry("code", "employeeCode"),
            Map.entry("empcode", "employeeCode"),
            Map.entry("fullname", "fullName"),
            Map.entry("name", "fullName"),
            Map.entry("email", "email"),
            Map.entry("emailaddress", "email"),
            Map.entry("department", "department"),
            Map.entry("dept", "department"),
            Map.entry("photofile", "photoFile"),
            Map.entry("photo", "photoFile"),
            Map.entry("photofilename", "photoFile"),
            Map.entry("idphoto", "photoFile"),
            Map.entry("image", "photoFile"),
            Map.entry("imagefile", "photoFile"));

    /** photoFile is deliberately absent: a row may arrive without a picture. */
    private static final List<String> REQUIRED =
            List.of("employeeCode", "fullName", "email", "department");

    private static final Pattern PICTURE_NAME = Pattern.compile("(?i).*\\.(jpe?g|png)$");

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    /** One row the import refused, numbered the way the spreadsheet numbers it. */
    public record RowProblem(int row, String employeeCode, String detail) {}

    /**
     * @param imported    employees created (0 whenever anything was wrong)
     * @param withPhoto   how many of those arrived with their ID/photo attached
     * @param rows        employee rows found in the file
     * @param problemRows rows with a problem, including any beyond those listed
     * @param problems    the first {@value #MAX_PROBLEMS_LISTED} of them, in file order
     */
    public record ImportReport(
            int imported, int withPhoto, int rows, int problemRows, List<RowProblem> problems) {}

    private final EmployeeRepository employees;
    private final ReferenceImageService referenceImages;
    private final AuditService audit;

    public EmployeeImportService(
            EmployeeRepository employees, ReferenceImageService referenceImages, AuditService audit) {
        this.employees = employees;
        this.referenceImages = referenceImages;
        this.audit = audit;
    }

    /** A file whose rows all leave photoFile empty; every employee arrives without a reference. */
    public ImportReport importCsv(byte[] content) {
        return importCsv(content, Map.of());
    }

    /**
     * @param photos the pictures chosen alongside the file, by file name; the
     *               {@code photoFile} column of a row picks one out of these
     */
    @Transactional
    public ImportReport importCsv(byte[] content, Map<String, byte[]> photos) {
        if (content == null || content.length == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The file is empty.");
        }
        var records = parseCsv(decode(content));
        if (records.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The file is empty.");
        }

        var column = headerColumns(records.get(0));
        var rows = records.subList(1, records.size());
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "The file has a header row but no employees under it.");
        }
        if (rows.size() > MAX_ROWS) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "This file has " + rows.size() + " employees; one upload may carry at most "
                            + MAX_ROWS + ". Split it and import the parts.");
        }

        // Windows file names are not case-sensitive and the CSV is typed by hand, so
        // "E-1001.JPG" in the folder and "e-1001.jpg" in the file are the same picture.
        var picturesByName = new HashMap<String, byte[]>();
        photos.forEach((name, bytes) -> picturesByName.put(name.toLowerCase(Locale.ROOT), bytes));

        var problems = new ArrayList<RowProblem>();
        var accepted = new ArrayList<NewEmployee>();
        // Lower-cased value -> the row that claimed it first, so the second one can
        // say which row it collides with instead of only "duplicate".
        var codesSeen = new HashMap<String, Integer>();
        var emailsSeen = new HashMap<String, Integer>();

        for (int i = 0; i < rows.size(); i++) {
            int row = i + 2;   // the header is row 1, exactly as a spreadsheet counts
            var fields = rows.get(i);
            String code = cell(fields, column, "employeeCode");
            String name = cell(fields, column, "fullName");
            String email = cell(fields, column, "email");
            String department = cell(fields, column, "department");
            String photoFile = column.containsKey("photoFile") ? cell(fields, column, "photoFile") : "";

            var faults = new ArrayList<String>();
            if (code.isEmpty()) {
                faults.add("employeeCode is empty");
            }
            if (name.isEmpty()) {
                faults.add("fullName is empty");
            }
            if (email.isEmpty()) {
                faults.add("email is empty");
            } else if (!EMAIL.matcher(email).matches()) {
                faults.add("\"" + email + "\" is not a valid email address");
            }
            String canonicalDepartment = canonicalDepartment(department);
            if (department.isEmpty()) {
                faults.add("department is empty");
            } else if (canonicalDepartment == null) {
                faults.add("department \"" + department + "\" is not one of "
                        + String.join(", ", DEPARTMENTS));
            }
            if (!code.isEmpty()) {
                Integer first = codesSeen.putIfAbsent(code.toLowerCase(Locale.ROOT), row);
                if (first != null) {
                    faults.add("employeeCode \"" + code + "\" is already used on row " + first);
                } else if (employees.existsByEmployeeCodeIgnoreCase(code)) {
                    faults.add("an employee with code \"" + code + "\" already exists");
                }
            }
            if (!email.isEmpty()) {
                Integer first = emailsSeen.putIfAbsent(email.toLowerCase(Locale.ROOT), row);
                if (first != null) {
                    faults.add("email \"" + email + "\" is already used on row " + first);
                } else if (employees.existsByEmailIgnoreCase(email)) {
                    faults.add("an employee with email \"" + email + "\" already exists");
                }
            }

            byte[] picture = null;
            if (!photoFile.isEmpty()) {
                picture = picturesByName.get(photoFile.toLowerCase(Locale.ROOT));
                if (!PICTURE_NAME.matcher(photoFile).matches()) {
                    // A PDF or a scan of an ID card is not a picture a face can be
                    // compared against, so it is refused here rather than stored and
                    // found unusable at the employee's first scan.
                    faults.add("photo \"" + photoFile + "\" has to be a .jpg or .png");
                } else if (picture == null) {
                    faults.add("photo \"" + photoFile + "\" was not among the pictures you chose");
                } else if (!ReferenceImageService.looksLikeImage(picture)) {
                    faults.add("photo \"" + photoFile + "\" is not a readable JPEG or PNG");
                }
            }

            if (faults.isEmpty()) {
                var employee = new Employee();
                employee.setEmployeeCode(code);
                employee.setFullName(name);
                employee.setEmail(email);
                employee.setDepartment(canonicalDepartment);
                accepted.add(new NewEmployee(employee, picture));
            } else {
                problems.add(new RowProblem(row, code, String.join("; ", faults)));
            }
        }

        if (!problems.isEmpty()) {
            var listed = problems.subList(0, Math.min(problems.size(), MAX_PROBLEMS_LISTED));
            return new ImportReport(0, 0, rows.size(), problems.size(), List.copyOf(listed));
        }

        int withPhoto = 0;
        for (var pending : accepted) {
            var employee = pending.employee();
            employees.save(employee);
            audit.record("admin", "EMPLOYEE_CREATED", "employee", employee.getId().toString(),
                    "{\"source\":\"csv-import\"}");
            if (pending.picture() != null) {
                referenceImages.storeAdminUpload(employee.getId(), pending.picture());
                audit.record("admin", "REFERENCE_IMAGE_UPLOADED", "employee", employee.getId().toString(),
                        "{\"source\":\"csv-import\"}");
                withPhoto++;
            }
        }
        return new ImportReport(accepted.size(), withPhoto, rows.size(), 0, List.of());
    }

    /** A row that passed, held until the whole file has, with the picture it named. */
    private record NewEmployee(Employee employee, byte[] picture) {}

    /** Maps each field this import needs onto the position it sits at in the file. */
    private static Map<String, Integer> headerColumns(List<String> header) {
        var column = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < header.size(); i++) {
            String field = COLUMNS.get(normalise(header.get(i)));
            if (field != null) {
                column.putIfAbsent(field, i);
            }
        }
        var missing = REQUIRED.stream().filter(field -> !column.containsKey(field)).toList();
        if (!missing.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "The first row of the file has to name the columns, and this one is missing "
                            + String.join(", ", missing)
                            + ". Expected: employeeCode,fullName,email,department");
        }
        return column;
    }

    private static String cell(List<String> fields, Map<String, Integer> column, String field) {
        int at = column.get(field);
        return at < fields.size() ? fields.get(at).trim() : "";
    }

    private static String normalise(String header) {
        return header.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String canonicalDepartment(String value) {
        return DEPARTMENTS.stream().filter(d -> d.equalsIgnoreCase(value)).findFirst().orElse(null);
    }

    /**
     * Excel on Windows writes the system codepage unless the administrator picks
     * "CSV UTF-8" by hand, so an accented name commonly arrives as cp1252. Read
     * the file as UTF-8 when it is valid UTF-8 and fall back when it is not,
     * rather than storing a mangled name.
     */
    static String decode(byte[] content) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException e) {
            text = new String(content, Charset.forName("windows-1252"));
        }
        // Excel prefixes a byte-order mark, which would otherwise ride along inside
        // the first header name and stop it matching "employeeCode".
        return text.startsWith("﻿") ? text.substring(1) : text;
    }

    /**
     * RFC 4180 reader, enough of it for a spreadsheet export: a quoted field may
     * hold commas, line breaks and doubled quotes. Rows where every field is
     * blank are dropped - a spreadsheet leaves those behind at the end of a file
     * and they are not failed employees.
     */
    static List<List<String>> parseCsv(String text) {
        var records = new ArrayList<List<String>>();
        var record = new ArrayList<String>();
        var field = new StringBuilder();
        boolean quoted = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c != '"') {
                    field.append(c);
                } else if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else {
                    quoted = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> quoted = true;
                case ',' -> {
                    record.add(field.toString());
                    field.setLength(0);
                }
                case '\r' -> { /* CRLF from Windows; the \n ends the row */ }
                case '\n' -> {
                    record.add(field.toString());
                    field.setLength(0);
                    addIfNotBlank(records, record);
                    record = new ArrayList<>();
                }
                default -> field.append(c);
            }
        }
        if (field.length() > 0 || !record.isEmpty()) {
            record.add(field.toString());
            addIfNotBlank(records, record);
        }
        return records;
    }

    private static void addIfNotBlank(List<List<String>> records, List<String> record) {
        if (record.stream().anyMatch(f -> !f.isBlank())) {
            records.add(List.copyOf(record));
        }
    }
}
