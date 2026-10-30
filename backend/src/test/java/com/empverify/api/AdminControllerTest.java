package com.empverify.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.empverify.domain.Employee;
import com.empverify.domain.Enums.ReferenceImageSource;
import com.empverify.domain.Enums.ReferenceImageStatus;
import com.empverify.domain.ReferenceImage;
import com.empverify.repo.CheckReviewRepository;
import com.empverify.repo.EmployeeRepository;
import com.empverify.repo.ReferenceImageRepository;
import com.empverify.repo.VerificationCheckRepository;
import com.empverify.service.ApiException;
import com.empverify.service.AuditService;
import com.empverify.service.DeviceService;
import com.empverify.storage.ImageStorage;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

/** Creating an employee requires their ID/photo: no image, no record. */
@ExtendWith(MockitoExtension.class)
class AdminControllerTest {

    @Mock private EmployeeRepository employees;
    @Mock private ReferenceImageRepository referenceImages;
    @Mock private VerificationCheckRepository checks;
    @Mock private CheckReviewRepository reviews;
    @Mock private DeviceService deviceService;
    @Mock private ImageStorage storage;
    @Mock private AuditService audit;

    private AdminController controller;

    private static final MockMultipartFile PHOTO =
            new MockMultipartFile("image", "id.jpg", "image/jpeg", new byte[] {1, 2, 3});

    @BeforeEach
    void setUp() {
        controller = new AdminController(employees, referenceImages, checks, reviews, deviceService, storage, audit);
    }

    @Test
    void createStoresEmployeeAndPhotoTogether() {
        when(storage.store(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(referenceImages.findByEmployeeIdAndStatusAndSource(any(), eq(ReferenceImageStatus.ACTIVE),
                eq(ReferenceImageSource.ADMIN_UPLOAD))).thenReturn(Optional.of(new ReferenceImage()));
        when(referenceImages.findByEmployeeIdAndStatusAndSource(any(), eq(ReferenceImageStatus.ACTIVE),
                eq(ReferenceImageSource.LIVE_ENROLLMENT))).thenReturn(Optional.empty());

        var response = controller.createEmployee(" E-100 ", "Pat Example", "pat@example.com", "", PHOTO);

        var saved = ArgumentCaptor.forClass(Employee.class);
        verify(employees).save(saved.capture());
        assertEquals("E-100", saved.getValue().getEmployeeCode());
        assertEquals("pat@example.com", saved.getValue().getEmail());
        assertEquals(null, saved.getValue().getDepartment());

        var reference = ArgumentCaptor.forClass(ReferenceImage.class);
        verify(referenceImages).save(reference.capture());
        assertEquals(saved.getValue().getId(), reference.getValue().getEmployeeId());
        assertEquals(ReferenceImageSource.ADMIN_UPLOAD, reference.getValue().getSource());
        verify(storage).store(eq("reference/" + saved.getValue().getId() + "/" + reference.getValue().getId() + ".jpg"),
                eq(new byte[] {1, 2, 3}));

        verify(audit).record(eq("admin"), eq("EMPLOYEE_CREATED"), eq("employee"), anyString(), any());
        verify(audit).record(eq("admin"), eq("REFERENCE_IMAGE_UPLOADED"), eq("employee"), anyString(), any());
        assertTrue(response.hasReferenceImage());
        assertFalse(response.hasLiveEnrollment());
    }

    @Test
    void createWithoutPhotoIsRefusedAndNothingIsSaved() {
        var e = assertThrows(ApiException.class,
                () -> controller.createEmployee("E-100", "Pat Example", "pat@example.com", null, null));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        assertTrue(e.getMessage().contains("photo or ID image is required"));
        verify(employees, never()).save(any());
        verify(storage, never()).store(anyString(), any());
    }

    @Test
    void createWithEmptyUploadIsRefused() {
        var empty = new MockMultipartFile("image", "id.jpg", "image/jpeg", new byte[0]);

        var e = assertThrows(ApiException.class,
                () -> controller.createEmployee("E-100", "Pat Example", "pat@example.com", null, empty));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        verify(employees, never()).save(any());
    }

    @Test
    void createWithNonImageUploadIsRefused() {
        var pdf = new MockMultipartFile("image", "id.pdf", "application/pdf", new byte[] {1});

        var e = assertThrows(ApiException.class,
                () -> controller.createEmployee("E-100", "Pat Example", "pat@example.com", null, pdf));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        assertTrue(e.getMessage().contains("JPEG or PNG"));
        verify(employees, never()).save(any());
    }

    @Test
    void createWithMissingDetailsOrBadEmailIsRefused() {
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ApiException.class,
                () -> controller.createEmployee("", "Pat", "pat@example.com", null, PHOTO)).getStatus());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ApiException.class,
                () -> controller.createEmployee("E-1", "  ", "pat@example.com", null, PHOTO)).getStatus());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ApiException.class,
                () -> controller.createEmployee("E-1", "Pat", "not-an-email", null, PHOTO)).getStatus());
        verify(employees, never()).save(any());
    }

    @Test
    void duplicateCodeOrEmailIsAConflict() {
        when(employees.existsByEmployeeCodeIgnoreCase("E-100")).thenReturn(true);

        var e = assertThrows(ApiException.class,
                () -> controller.createEmployee("E-100", "Pat Example", "pat@example.com", null, PHOTO));

        assertEquals(HttpStatus.CONFLICT, e.getStatus());
        verify(employees, never()).save(any());
        verify(storage, never()).store(anyString(), any());
    }

    @Test
    void requestingReEnrollmentRetiresTheLiveReferenceAndResetsTheSystems() {
        var employeeId = UUID.randomUUID();
        when(employees.findById(employeeId)).thenReturn(Optional.of(new Employee()));
        var live = new ReferenceImage();
        live.setSource(ReferenceImageSource.LIVE_ENROLLMENT);
        when(referenceImages.findByEmployeeIdAndStatusAndSource(
                employeeId, ReferenceImageStatus.ACTIVE, ReferenceImageSource.LIVE_ENROLLMENT))
                .thenReturn(Optional.of(live));
        when(deviceService.resetEnrollmentForEmployee(employeeId)).thenReturn(2);

        var response = controller.requestReEnrollment(employeeId);

        assertEquals(2, response.systemsReset());
        assertEquals(ReferenceImageStatus.REPLACED, live.getStatus());
        verify(audit).record(eq("admin"), eq("RE_ENROLLMENT_REQUESTED"), eq("employee"), eq(employeeId.toString()), any());
    }

    @Test
    void replacingThePhotoWithoutAnImageIsRefused() {
        var employeeId = UUID.randomUUID();
        when(employees.findById(employeeId)).thenReturn(Optional.of(new Employee()));

        var e = assertThrows(ApiException.class, () -> controller.uploadReferenceImage(employeeId, null));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        verify(referenceImages, never()).save(any());
    }
}
