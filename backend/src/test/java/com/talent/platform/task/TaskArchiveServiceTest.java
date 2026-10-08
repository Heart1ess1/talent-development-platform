package com.talent.platform.task;

import com.talent.platform.common.BusinessException;
import com.talent.platform.security.*;
import com.talent.platform.storage.FileStorageService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskArchiveServiceTest {
  @TempDir Path root;
  JdbcTemplate db;
  FileStorageService storage;
  PermissionService permissions;
  TaskArchiveService service;

  @BeforeEach void setup() throws Exception {
    db = mock(JdbcTemplate.class);
    storage = mock(FileStorageService.class);
    permissions = mock(PermissionService.class);
    authenticate(7L);
    when(permissions.employeeFilter("e")).thenReturn(new PermissionService.ScopeFilter("", List.of()));
    when(db.queryForList(eq("select title from challenge_task where id=?"), eq(18L)))
        .thenReturn(List.of(Map.of("title", "月报")));
    when(db.queryForList(contains("from task_submission s join"), any(Object[].class)))
        .thenReturn(List.of(Map.of("id", 99L, "employee_id", 12L, "employee_name", "员工",
            "employee_no", "000123456789", "submission_version", 2, "content", "第二版说明")));
    when(db.queryForList(startsWith("select id,original_name,storage_key,size"), eq(99L)))
        .thenReturn(List.of(Map.of("id", 1L, "original_name", "月报.docx", "storage_key", "one", "size", 3L),
            Map.of("id", 2L, "original_name", "附件.pdf", "storage_key", "two", "size", 3L)));
    when(db.queryForObject(startsWith("select count(*) from employee e"), eq(Integer.class), any(Object[].class)))
        .thenReturn(1);
    when(storage.load(anyString())).thenReturn(new ByteArrayResource(new byte[]{1, 2, 3}));
    service = new TaskArchiveService(db, storage, permissions, mock(TaskScoringService.class), root.toString());
    service.initialize();
  }

  @AfterEach void teardown() throws Exception {
    service.shutdown();
    // Let the worker close handles before JUnit removes the temporary directory on Windows.
    var executor = (java.util.concurrent.ExecutorService) org.springframework.test.util.ReflectionTestUtils.getField(service, "executor");
    assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    SecurityContextHolder.clearContext();
  }

  void authenticate(Long id) {
    var user = new CurrentUser(id, "admin", "Admin", "ADMIN", false, 1,
        Set.of(Permissions.TASK_MANAGE), "ALL");
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
  }

  TaskArchiveService.Status finished(String id) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    TaskArchiveService.Status status;
    do {
      status = service.get(id);
      if (List.of("READY", "FAILED").contains(status.state())) return status;
      Thread.sleep(10);
    } while (System.nanoTime() < deadline);
    throw new AssertionError("Archive did not finish");
  }

  @Test void generatesCompleteArchiveAndDownloadHasExactLength() throws Exception {
    var status = finished(service.create(18L).jobId());
    assertThat(status.state()).isEqualTo("READY");
    assertThat(status.totalFiles()).isEqualTo(2);
    assertThat(status.processedFiles()).isEqualTo(2);
    var response = new MockHttpServletResponse();
    service.download(status.jobId(), response);
    assertThat(response.getHeader("Content-Length")).isEqualTo(String.valueOf(response.getContentAsByteArray().length));
    assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-store");
    try (var zip = new ZipInputStream(new ByteArrayInputStream(response.getContentAsByteArray()))) {
      assertThat(zip.getNextEntry().getName()).isEqualTo("员工（000123456789）/第2版/提交说明.txt");
      assertThat(new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("第二版说明");
      assertThat(zip.getNextEntry().getName()).endsWith("1-月报.docx");
      assertThat(zip.readAllBytes()).containsExactly(1, 2, 3);
      assertThat(zip.getNextEntry().getName()).endsWith("2-附件.pdf");
      assertThat(zip.readAllBytes()).containsExactly(1, 2, 3);
      assertThat(zip.getNextEntry().getName()).endsWith("人员信息.txt");
      assertThat(new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).contains("000123456789");
      assertThat(zip.getNextEntry()).isNull();
    }
    assertThat(root.resolve(status.jobId() + ".part")).doesNotExist();
  }

  @Test void closesEachAttachmentBeforeOpeningNextAndReportsProgress() throws Exception {
    var opened = new AtomicInteger();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(storage.load("one")).thenAnswer(invocation -> {
      opened.incrementAndGet();
      return new InputStreamResource(new ByteArrayInputStream(new byte[]{1, 2, 3}) {
        @Override public void close() throws IOException { opened.decrementAndGet(); super.close(); }
      });
    });
    when(storage.load("two")).thenAnswer(invocation -> {
      assertThat(opened.get()).isZero();
      entered.countDown();
      assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
      return new ByteArrayResource(new byte[]{1, 2, 3});
    });
    var job = service.create(18L);
    try {
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(service.get(job.jobId()).processedFiles()).isEqualTo(1);
      assertThat(service.get(job.jobId()).currentFile()).isEqualTo("附件.pdf");
      assertThat(service.create(18L).jobId()).isEqualTo(job.jobId());
      assertThatThrownBy(() -> service.download(job.jobId(), new MockHttpServletResponse()))
          .isInstanceOf(BusinessException.class).hasMessageContaining("尚未");
    } finally { release.countDown(); }
    assertThat(finished(job.jobId()).state()).isEqualTo("READY");
  }

  @Test void onlyCreatorCanReadOrDownloadEvenWhenOtherUserIsAdmin() throws Exception {
    var job = finished(service.create(18L).jobId());
    authenticate(8L);
    assertThatThrownBy(() -> service.get(job.jobId())).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> service.download(job.jobId(), new MockHttpServletResponse()))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test void rechecksEmployeeScopeAfterArchiveIsReady() throws Exception {
    var job = finished(service.create(18L).jobId());
    when(db.queryForObject(startsWith("select count(*) from employee e"), eq(Integer.class), any(Object[].class)))
        .thenReturn(0);
    assertThatThrownBy(() -> service.get(job.jobId())).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> service.download(job.jobId(), new MockHttpServletResponse()))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test void missingFileIsDescribedAndCountedButStorageFailureFailsJob() throws Exception {
    when(storage.load("two")).thenThrow(new BusinessException(404, "文件不存在"));
    var job = finished(service.create(18L).jobId());
    assertThat(job.state()).isEqualTo("READY");
    assertThat(job.missingFiles()).isEqualTo(1);
    assertThat(job.processedFiles()).isEqualTo(2);
    doThrow(new BusinessException(500, "存储失败")).when(storage).load("two");
    var failed = finished(service.create(18L).jobId());
    assertThat(failed.state()).isEqualTo("FAILED");
    assertThat(root.resolve(failed.jobId() + ".zip")).doesNotExist();
    assertThat(root.resolve(failed.jobId() + ".part")).doesNotExist();
    assertThatThrownBy(() -> service.download(failed.jobId(), new MockHttpServletResponse()))
        .isInstanceOf(BusinessException.class);
  }

  @Test void truncatedAttachmentFailsInsteadOfReturningIncompleteZip() throws Exception {
    when(storage.load("two")).thenReturn(new ByteArrayResource(new byte[]{1}));
    var job = finished(service.create(18L).jobId());
    assertThat(job.state()).isEqualTo("FAILED");
    assertThat(root.resolve(job.jobId() + ".zip")).doesNotExist();
  }

  @Test void expiredJobsAreRejectedAndArtifactsRemoved() throws Exception {
    var job = finished(service.create(18L).jobId());
    var jobs = (Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(service, "jobs");
    org.springframework.test.util.ReflectionTestUtils.setField(jobs.get(job.jobId()), "expiresAt", Instant.now().minusSeconds(1));
    assertThatThrownBy(() -> service.get(job.jobId())).isInstanceOf(BusinessException.class).hasMessageContaining("过期");
    service.cleanup();
    assertThat(root.resolve(job.jobId() + ".zip")).doesNotExist();
  }

  @Test void filtersSnapshotByCurrentEmployeeScopeAndRefusesEmptyTask() {
    when(permissions.employeeFilter("e")).thenReturn(new PermissionService.ScopeFilter(" and e.mentor_user_id=?", List.of(7L)));
    when(db.queryForList(contains("from task_submission s join"), any(Object[].class))).thenReturn(List.of());
    assertThatThrownBy(() -> service.create(18L)).isInstanceOf(BusinessException.class).hasMessageContaining("暂无");
    verify(db).queryForList(contains("where a.task_id=?\n and e.mentor_user_id=?"), eq(18L), eq(7L));
    verify(storage, never()).load(anyString());
  }

  @Test void rejectsOversizedJobBeforeReadingStorage() {
    when(db.queryForList(startsWith("select id,original_name,storage_key,size"), eq(99L)))
        .thenReturn(List.of(Map.of("id", 1L, "original_name", "huge.zip", "storage_key", "one", "size", 3L * 1024 * 1024 * 1024)));
    assertThatThrownBy(() -> service.create(18L)).isInstanceOf(BusinessException.class).hasMessageContaining("2 GiB");
    verify(storage, never()).load(anyString());
  }

  @Test void boundsQueueAndAllowsOnlyOneWorker() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(storage.load("one")).thenAnswer(invocation -> {
      entered.countDown();
      assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
      return new ByteArrayResource(new byte[]{1, 2, 3});
    });
    service.create(18L);
    try {
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      authenticate(8L);
      assertThat(service.create(18L).state()).isEqualTo("QUEUED");
      authenticate(9L);
      assertThat(service.create(18L).state()).isEqualTo("QUEUED");
      authenticate(10L);
      assertThatThrownBy(() -> service.create(18L)).isInstanceOf(BusinessException.class).hasMessageContaining("排队");
      verify(storage, times(1)).load("one");
    } finally { release.countDown(); }
  }
}
