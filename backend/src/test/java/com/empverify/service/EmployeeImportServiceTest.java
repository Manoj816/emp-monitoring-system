package com.empverify.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.empverify.domain.Employee;
import com.empverify.repo.EmployeeRepository;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * A CSV import is only worth having if the administrator can trust what came
 * back: either the whole file landed, or nothing did and every bad row is named.
 */
@ExtendWith(MockitoExtension.class)
class EmployeeImportServiceTest {

    @Mock private EmployeeRepository employees;
    @Mock private ReferenceImageService referenceImages;
    @Mock private AuditService audit;

    @InjectMocks private EmployeeImportService service;

    private static final String HEADER = "domainId,fullName,email,department\n";

    private EmployeeImportService.ImportReport importing(String csv) {
        return service.importCsv(csv.getBytes(StandardCharsets.UTF_8));
    }

    private List<Employee> saved(int expected) {
        var captor = ArgumentCaptor.forClass(Employee.class);
        verify(employees, times(expected)).save(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void importsEveryRowOfAWellFormedFile() {
        var report = importing(HEADER
                + "E-1001,Asha Menon,asha.menon@example.com,Engineering\n"
                + "E-1002,Sam Price,sam.price@example.com,Finance\n");

        assertEquals(2, report.imported());
        assertEquals(2, report.rows());
        assertEquals(0, report.problemRows());
        assertTrue(report.problems().isEmpty());

        var people = saved(2);
        assertEquals("E-1001", people.get(0).getEmployeeCode());
        assertEquals("Asha Menon", people.get(0).getFullName());
        assertEquals("asha.menon@example.com", people.get(0).getEmail());
        assertEquals("Engineering", people.get(0).getDepartment());
        assertEquals("Finance", people.get(1).getDepartment());
    }

    @Test
    void theImportIsRecordedAsWhereEachEmployeeCameFrom() {
        importing(HEADER + "E-1001,Asha Menon,asha.menon@example.com,Engineering\n");

        verify(audit).record(eq("admin"), eq("EMPLOYEE_CREATED"), eq("employee"), anyString(),
                eq("{\"source\":\"csv-import\"}"));
    }

    @Test
    void oneBadRowStopsTheWholeFile() {
        // Half an import is the worst outcome: the portal cannot show which half
        // arrived, and the corrected file then collides with the rows that did.
        var report = importing(HEADER
                + "E-1001,Asha Menon,asha.menon@example.com,Engineering\n"
                + "E-1002,,sam.price@example.com,Finance\n");

        assertEquals(0, report.imported());
        assertEquals(1, report.problemRows());
        assertEquals(3, report.problems().get(0).row(), "the header is row 1, as a spreadsheet counts");
        assertTrue(report.problems().get(0).detail().contains("fullName is empty"));
        verify(employees, never()).save(any());
    }

    @Test
    void everyProblemInTheFileIsReportedAtOnce() {
        // So one round of corrections is enough, rather than one upload per fault.
        var report = importing(HEADER
                + ",Asha Menon,asha.menon@example.com,Engineering\n"
                + "E-1002,Sam Price,not-an-email,Finance\n"
                + "E-1003,Lee Chan,lee.chan@example.com,Wizardry\n");

        assertEquals(3, report.problemRows());
        assertEquals(List.of(2, 3, 4), report.problems().stream().map(p -> p.row()).toList());
        assertTrue(report.problems().get(1).detail().contains("not a valid email address"));
        assertTrue(report.problems().get(2).detail().contains("not one of"));
    }

    @Test
    void aRowWithSeveralFaultsListsThemAllTogether() {
        var report = importing(HEADER + ",Sam Price,nope,Finance\n");

        var detail = report.problems().get(0).detail();
        assertTrue(detail.contains("domainId is empty"), detail);
        assertTrue(detail.contains("not a valid email address"), detail);
    }

    @Test
    void aDuplicateInsideTheFileNamesTheRowItCollidesWith() {
        var report = importing(HEADER
                + "E-1001,Asha Menon,asha.menon@example.com,Engineering\n"
                + "e-1001,Sam Price,sam.price@example.com,Finance\n");

        assertEquals(1, report.problemRows());
        assertEquals(3, report.problems().get(0).row());
        assertTrue(report.problems().get(0).detail().contains("already used on row 2"),
                report.problems().get(0).detail());
    }

    @Test
    void anEmployeeTheSystemAlreadyHasIsRejectedRatherThanDuplicated() {
        when(employees.existsByEmailIgnoreCase("asha.menon@example.com")).thenReturn(true);

        var report = importing(HEADER + "E-1001,Asha Menon,asha.menon@example.com,Engineering\n");

        assertEquals(0, report.imported());
        assertTrue(report.problems().get(0).detail().contains("already exists"));
        assertEquals("E-1001", report.problems().get(0).domainId(),
                "the report names the row by its code, so it can be found in the file");
    }

    @Test
    void anUnknownDepartmentIsRefusedRatherThanInvented() {
        // The assign-system popup filters by the fixed list, so a typo would create
        // people that no filter ever finds.
        var report = importing(HEADER + "E-1001,Asha Menon,asha.menon@example.com,Enginering\n");

        assertEquals(0, report.imported());
        assertTrue(report.problems().get(0).detail().contains("Enginering"));
    }

    @Test
    void departmentCasingInTheFileDoesNotMatter() {
        var report = importing(HEADER + "E-1001,Asha Menon,asha.menon@example.com,human resources\n");

        assertEquals(1, report.imported());
        assertEquals("Human Resources", saved(1).get(0).getDepartment(),
                "stored as the one spelling the portal filters by");
    }

    @Test
    void quotedFieldsKeepTheirCommasAndQuotes() {
        var report = importing(HEADER
                + "E-1001,\"Menon, Asha\",asha.menon@example.com,Engineering\n"
                + "E-1002,\"Sam \"\"Sammy\"\" Price\",sam.price@example.com,Finance\n");

        assertEquals(2, report.imported());
        var people = saved(2);
        assertEquals("Menon, Asha", people.get(0).getFullName());
        assertEquals("Sam \"Sammy\" Price", people.get(1).getFullName());
    }

    @Test
    void theBlankRowsASpreadsheetLeavesBehindAreNotFailedEmployees() {
        var report = importing(HEADER
                + "E-1001,Asha Menon,asha.menon@example.com,Engineering\r\n"
                + "\r\n"
                + ",,,\r\n");

        assertEquals(1, report.imported());
        assertEquals(1, report.rows());
        assertEquals(0, report.problemRows());
    }

    @Test
    void headerNamesDoNotHaveToBeSpeltExactly() {
        var report = importing("Domain ID,Full Name,Email Address,Dept\n"
                + "E-1001,Asha Menon,asha.menon@example.com,Engineering\n");

        assertEquals(1, report.imported());
    }

    @Test
    void columnsMayComeInAnyOrder() {
        var report = importing("email,department,domainId,fullName\n"
                + "asha.menon@example.com,Engineering,E-1001,Asha Menon\n");

        assertEquals(1, report.imported());
        var employee = saved(1).get(0);
        assertEquals("E-1001", employee.getEmployeeCode());
        assertEquals("asha.menon@example.com", employee.getEmail());
    }

    @Test
    void aFileWithoutTheColumnsItNeedsSaysWhichOnesAreMissing() {
        var thrown = assertThrows(ApiException.class,
                () -> importing("domainId,fullName\nE-1001,Asha Menon\n"));

        assertTrue(thrown.getMessage().contains("email"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("department"), thrown.getMessage());
    }

    @Test
    void aHeaderWithNoEmployeesUnderItSaysSo() {
        var thrown = assertThrows(ApiException.class, () -> importing(HEADER));
        assertTrue(thrown.getMessage().contains("no employees"), thrown.getMessage());
    }

    @Test
    void aFileTooLongForOneUploadIsRefusedBeforeAnythingIsStored() {
        var csv = new StringBuilder(HEADER);
        for (int i = 0; i <= EmployeeImportService.MAX_ROWS; i++) {
            csv.append("E-").append(i).append(",Person ").append(i)
                    .append(",person").append(i).append("@example.com,Engineering\n");
        }

        var thrown = assertThrows(ApiException.class, () -> importing(csv.toString()));

        assertTrue(thrown.getMessage().contains(String.valueOf(EmployeeImportService.MAX_ROWS)));
        verify(employees, never()).save(any());
    }

    @Test
    void aSpreadsheetSavedAsAnsiKeepsItsAccentedNames() {
        // Excel on Windows writes the system codepage unless "CSV UTF-8" is picked
        // by hand, which is not the obvious choice in the Save As dialog.
        var csv = HEADER + "E-1001,Jose Munoz,jose@example.com,Engineering\n";
        var ansi = csv.replace("Jose Munoz", "José Muñoz").getBytes(Charset.forName("windows-1252"));

        var report = service.importCsv(ansi);

        assertEquals(1, report.imported());
        assertEquals("José Muñoz", saved(1).get(0).getFullName());
    }

    @Test
    void theByteOrderMarkExcelWritesDoesNotHideTheFirstColumn() {
        var withBom = ("﻿" + HEADER + "E-1001,Asha Menon,asha.menon@example.com,Engineering\n")
                .getBytes(StandardCharsets.UTF_8);

        assertEquals(1, service.importCsv(withBom).imported());
    }

    @Test
    void anEmptyFileIsRefused() {
        assertThrows(ApiException.class, () -> service.importCsv(new byte[0]));
    }

    // --- the picture the row carries -------------------------------------

    private static final String PHOTO_HEADER =
            "domainId,fullName,email,department,photoBase64\n";

    /** Smallest thing that passes the magic-byte check: a JPEG starts FF D8 FF. */
    private static byte[] jpeg() {
        return new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 1, 2};
    }

    private static String encoded() {
        return Base64.getEncoder().encodeToString(jpeg());
    }

    private static String rowWith(String photoCell) {
        return PHOTO_HEADER + "E-1001,Asha Menon,asha.menon@example.com,Engineering," + photoCell + "\n";
    }

    @Test
    void theRowsOwnPictureIsStoredAsTheEmployeesReference() {
        var report = importing(rowWith(encoded()));

        assertEquals(1, report.imported());
        assertEquals(1, report.withPhoto());
        var employeeId = saved(1).get(0).getId();
        verify(referenceImages).storeAdminUpload(eq(employeeId), eq(jpeg()));
    }

    @Test
    void aRowMayLeaveThePictureForLater() {
        var report = importing(rowWith(""));

        assertEquals(1, report.imported(), "the record is worth having before the photo is");
        assertEquals(0, report.withPhoto());
        verify(referenceImages, never()).storeAdminUpload(any(), any());
    }

    @Test
    void aDataUriPrefixIsToleratedBecauseExportsCarryOne() {
        // Quoted, because a data: URI has a comma in it.
        var report = importing(rowWith("\"data:image/jpeg;base64," + encoded() + "\""));

        assertEquals(1, report.imported());
        assertEquals(1, report.withPhoto());
    }

    @Test
    void lineBreaksInsideTheBase64AreTolerated() {
        var wrapped = encoded().substring(0, 4) + "\n" + encoded().substring(4);
        var report = importing(rowWith("\"" + wrapped + "\""));

        assertEquals(1, report.imported());
        assertEquals(1, report.withPhoto());
    }

    @Test
    void somethingThatIsNotBase64AtAllIsRefused() {
        var report = importing(rowWith("not base64 $$$"));

        assertEquals(0, report.imported());
        assertTrue(report.problems().get(0).detail().contains("not valid base64"),
                report.problems().get(0).detail());
        verify(employees, never()).save(any());
    }

    @Test
    void validBase64ThatIsNotAPictureIsRefused() {
        // A PDF of an ID card encodes perfectly well; a face cannot be compared
        // against it, so it must not become somebody's reference image.
        var pdf = Base64.getEncoder().encodeToString("%PDF-1.7 an ID card".getBytes(StandardCharsets.UTF_8));
        var report = importing(rowWith(pdf));

        assertEquals(0, report.imported());
        assertTrue(report.problems().get(0).detail().contains("not a JPEG or PNG"),
                report.problems().get(0).detail());
    }

    @Test
    void aPictureTooBigForARowIsRefused() {
        var huge = new byte[EmployeeImportService.MAX_PICTURE_BYTES + 1];
        huge[0] = (byte) 0xFF;
        huge[1] = (byte) 0xD8;
        huge[2] = (byte) 0xFF;
        var report = importing(rowWith(Base64.getEncoder().encodeToString(huge)));

        assertEquals(0, report.imported());
        assertTrue(report.problems().get(0).detail().contains("bigger than"),
                report.problems().get(0).detail());
    }

    @Test
    void nothingIsStoredWhenOnlyOneRowsPictureIsBad() {
        var report = importing(PHOTO_HEADER
                + "E-1001,Asha Menon,asha.menon@example.com,Engineering," + encoded() + "\n"
                + "E-1002,Sam Price,sam.price@example.com,Finance,@@@not base64@@@\n");

        assertEquals(0, report.imported());
        verify(employees, never()).save(any());
        verify(referenceImages, never()).storeAdminUpload(any(), any());
    }

    @Test
    void aFileWithNoPhotoColumnAtAllStillImports() {
        var report = importing(HEADER + "E-1001,Asha Menon,asha.menon@example.com,Engineering\n");

        assertEquals(1, report.imported());
        assertEquals(0, report.withPhoto());
    }
}
