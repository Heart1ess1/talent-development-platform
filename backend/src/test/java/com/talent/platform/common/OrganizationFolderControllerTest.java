package com.talent.platform.common;

import com.talent.platform.security.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class OrganizationFolderControllerTest {
  JdbcTemplate db=mock(JdbcTemplate.class);
  PermissionService permissions=mock(PermissionService.class);
  OrganizationFolderController controller=new OrganizationFolderController(db,permissions,mock(AuditService.class));

  @Test void checksIndependentPermissionsAndRejectsUnknownDomain(){
    controller.list("training-plans"); controller.list("question-banks");
    verify(permissions).require(Permissions.TASK_MANAGE);
    verify(permissions).require(Permissions.EXAM_MANAGE);
    assertThatThrownBy(()->controller.list("sys_user")).isInstanceOf(BusinessException.class);
  }
  @Test void unauthorizedMoveCannotQueryOrWrite(){
    doThrow(new AccessDeniedException("denied")).when(permissions).require(Permissions.TASK_MANAGE);
    assertThatThrownBy(()->controller.move("training-plans",new OrganizationFolderController.MoveRequest(List.of(1L),2L)))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(db);
  }
  @Test void validatesDestinationBeforeMoving(){
    when(db.queryForList(anyString(),eq(9L))).thenReturn(List.of());
    assertThatThrownBy(()->controller.move("question-banks",new OrganizationFolderController.MoveRequest(List.of(1L),9L)))
        .isInstanceOf(BusinessException.class).hasMessageContaining("文件夹不存在");
    verify(db,never()).update(anyString(),any(Object[].class));
  }
  @Test void validatesEveryItemBeforeUpdating(){
    when(db.queryForList(contains("select id,folder_id"),any(Object[].class))).thenReturn(List.of(Map.of("id",1L)));
    assertThatThrownBy(()->controller.move("training-plans",new OrganizationFolderController.MoveRequest(List.of(1L,2L),null)))
        .isInstanceOf(BusinessException.class).hasMessageContaining("部分内容");
    verify(db,never()).update(anyString(),any(Object[].class));
  }
  @Test void movesItemsToUncategorizedWithoutChangingBusinessFields(){
    when(db.queryForList(contains("select id,folder_id"),any(Object[].class))).thenReturn(List.of(Map.of("id",1L)));
    controller.move("training-plans",new OrganizationFolderController.MoveRequest(List.of(1L,1L),null));
    verify(db).update(eq("update training_plan set folder_id=? where id in (?)"),org.mockito.AdditionalMatchers.aryEq(new Object[]{null,1L}));
  }
  @Test void deletesOnlyFolderAndReliesOnSetNullForeignKeys(){
    when(db.queryForList(anyString(),eq(3L))).thenReturn(List.of(Map.of("id",3L,"name","月报")));
    controller.delete("training-plans",3L);
    verify(db).update("delete from training_plan_folder where id=?",3L);
    verify(db,never()).update(startsWith("delete from training_plan where"),any(Object[].class));
  }
}
