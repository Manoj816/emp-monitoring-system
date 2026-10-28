package com.empverify.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.empverify.storage.ImageStorage;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class DemoResetServiceTest {

    @Mock private JdbcTemplate jdbc;
    @Mock private ImageStorage storage;
    @Mock private AuditService audit;

    @Test
    void refusesToRunUnlessExplicitlyEnabled() {
        var service = new DemoResetService(jdbc, storage, audit, false);

        assertThrows(ApiException.class, () -> service.reset("admin:test"));
        verifyNoInteractions(jdbc, storage, audit);
    }

    @Test
    void wipesTablesDeletesStoredImagesAndAuditsTheReset() {
        when(jdbc.queryForList(contains("reference_images"), eq(String.class)))
                .thenReturn(List.of("reference/a.jpg"));
        when(jdbc.queryForList(contains("verification_checks"), eq(String.class)))
                .thenReturn(List.of("evidence/b.jpg"));
        when(jdbc.queryForObject(startsWith("SELECT count(*)"), eq(Integer.class)))
                .thenReturn(2, 1, 5); // employees, devices, checks
        var service = new DemoResetService(jdbc, storage, audit, true);

        var summary = service.reset("admin:test");

        verify(jdbc).execute(contains("TRUNCATE TABLE"));
        verify(storage).delete("reference/a.jpg");
        verify(storage).delete("evidence/b.jpg");
        verify(audit).record(eq("admin:test"), eq("DEMO_RESET"), eq("system"), eq("all"), anyString());
        assertEquals(2, summary.employees());
        assertEquals(1, summary.devices());
        assertEquals(5, summary.checks());
        assertEquals(2, summary.imagesDeleted());
    }
}
