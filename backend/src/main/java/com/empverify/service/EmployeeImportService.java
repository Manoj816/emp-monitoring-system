package com.empverify.service;

import com.empverify.domain.Employee;
import com.empverify.repo.EmployeeRepository;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
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
 * <p>A row carries its own ID/photo, base64-encoded, in the optional
 * {@code photoBase64} column. Everything an employee needs is then in their row:
 * nothing to pick alongside the file, nothing for the server to go and fetch,
 * and no way for a picture to go missing between the file being written and
 * being uploaded.
 *
 * <p>The cost of that is size, and it is worth being plain about it: base64 is
 * about a third larger than the picture it carries, so a row holding a 60 KB
 * photo is an 80 KB row. A few hundred employees with pictures make a file of
 * tens of megabytes - which is what {@code spring.servlet.multipart} is sized
 * for - and tens of thousands would not fit in one upload at all. A file that
 * large has to leave {@code photoBase64} empty and have the pictures uploaded
 * per person afterwards, or be split.
 *
 * <p>{@code photoBase64} may be left empty, and that employee arrives with no
 * reference: listed as "ID / photo: missing", counted by the "Photo missing"
 * tile, and not verifiable until somebody uploads one, because a check needs
 * something to compare a face against. The roster is worth having before the
 * pictures are. The single-employee form still demands a photo, because there
 * nothing is gained by deferring it.
 */
@Service
public class EmployeeImportService {

    /** Rows, not counting the header, that one upload may carry. */
    public static final int MAX_ROWS = 5000;

    /**
     * Decoded size of one row's picture. A reference image is a head-and-shoulders
     * photo or a scan of an ID; past this it is something else, and a file full of
     * them would not survive the upload limit anyway.
     */
    public static final int MAX_PICTURE_BYTES = 4 * 1024 * 1024;

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
            List.of("Engineering", "Sales", "Human Resources", "Finance", "Call Center");

    /** Header names accepted for each field, so a hand-made file does not have to guess. */
    private static final Map<String, String> COLUMNS = Map.ofEntries(
            Map.entry("domainid", "domainId"),
            Map.entry("domain", "domainId"),
            Map.entry("employeecode", "domainId"),
            Map.entry("code", "domainId"),
            Map.entry("empcode", "domainId"),
            Map.entry("fullname", "fullName"),
            Map.entry("name", "fullName"),
            Map.entry("email", "email"),
            Map.entry("emailaddress", "email"),
            Map.entry("department", "department"),
            Map.entry("dept", "department"),
            Map.entry("photobase64", "photoBase64"),
            Map.entry("photo", "photoBase64"),
            Map.entry("photodata", "photoBase64"),
            Map.entry("idphoto", "photoBase64"),
            Map.entry("imagebase64", "photoBase64"));

    /** photoBase64 is deliberately absent: a row may arrive without a picture. */
    private static final List<String> REQUIRED =
            List.of("domainId", "fullName", "email", "department");

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    /** One row the import refused, numbered the way the spreadsheet numbers it. */
    public record RowProblem(int row, String domainId, String detail) {}

    /**
     * @param imported    employees created (0 whenever anything was wrong)
     * @param withPhoto   how many of those carried an ID/photo in their row
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

    @Transactional
    public ImportReport importCsv(byte[] content) {
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

        var problems = new ArrayList<RowProblem>();
        var accepted = new ArrayList<PendingEmployee>();
        // Lower-cased value -> the row that claimed it first, so the second one can
        // say which row it collides with instead of only "duplicate".
        var codesSeen = new HashMap<String, Integer>();
        var emailsSeen = new HashMap<String, Integer>();

        for (int i = 0; i < rows.size(); i++) {
            int row = i + 2;   // the header is row 1, exactly as a spreadsheet counts
            var fields = rows.get(i);
            String code = cell(fields, column, "domainId");
            String name = cell(fields, column, "fullName");
            String email = cell(fields, column, "email");
            String department = cell(fields, column, "department");
            String photoData = column.containsKey("photoBase64") ? cell(fields, column, "photoBase64") : "";

            var faults = new ArrayList<String>();
            if (code.isEmpty()) {
                faults.add("domainId is empty");
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
                    faults.add("domainId \"" + code + "\" is already used on row " + first);
                } else if (employees.existsByEmployeeCodeIgnoreCase(code)) {
                    faults.add("an employee with Domain ID \"" + code + "\" already exists");
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
            if (!photoData.isEmpty()) {
                var decoded = decodePicture(photoData);
                if (decoded.problem() != null) {
                    faults.add("photoBase64 " + decoded.problem());
                } else {
                    picture = decoded.picture();
                }
            }

            if (faults.isEmpty()) {
                var employee = new Employee();
                employee.setEmployeeCode(code);
                employee.setFullName(name);
                employee.setEmail(email);
                employee.setDepartment(canonicalDepartment);
                accepted.add(new PendingEmployee(employee, picture));
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

    /** A row that passed, held until the whole file has, with the picture it carried. */
    private record PendingEmployee(Employee employee, byte[] picture) {}

    /** A decoded picture, or the reason the cell did not yield one. */
    record Picture(byte[] picture, String problem) {}

    /**
     * Turns one {@code photoBase64} cell into image bytes.
     *
     * <p>Tolerates the two things real exports carry: a {@code data:image/...;base64,}
     * prefix, and line breaks inside the base64. What it does not tolerate is
     * something that decodes cleanly but is not a picture - that is judged by the
     * leading bytes, so a stray PDF or a block of text cannot quietly become
     * somebody's reference image.
     */
    static Picture decodePicture(String value) {
        String data = value;
        if (data.regionMatches(true, 0, "data:", 0, 5)) {
            int comma = data.indexOf(',');
            if (comma < 0) {
                return new Picture(null, "looks like a data: URI but has no comma before the data");
            }
            data = data.substring(comma + 1);
        }
        data = data.replaceAll("\\s", "");
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(data);
        } catch (IllegalArgumentException e) {
            return new Picture(null, "is not valid base64");
        }
        if (bytes.length > MAX_PICTURE_BYTES) {
            return new Picture(null, "is bigger than " + (MAX_PICTURE_BYTES / (1024 * 1024)) + " MB");
        }
        if (!ReferenceImageService.looksLikeImage(bytes)) {
            return new Picture(null, "is not a JPEG or PNG picture");
        }
        return new Picture(bytes, null);
    }

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
                            + ". Expected: domainId,fullName,email,department,photoBase64");
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
