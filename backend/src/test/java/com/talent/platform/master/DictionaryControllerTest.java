package com.talent.platform.master;

import com.talent.platform.common.BusinessException;
import com.talent.platform.security.AuditService;
import com.talent.platform.security.PermissionService;
import com.talent.platform.security.Permissions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DictionaryControllerTest {
  private JdbcTemplate db;
  private PermissionService permissions;
  private AuditService audit;
  private DictionaryController controller;

  @BeforeEach
  void setUp() {
    db = mock(JdbcTemplate.class);
    permissions = mock(PermissionService.class);
    audit = mock(AuditService.class);
    controller = new DictionaryController(db, permissions, audit);
  }

  @Test
  void listsEnabledDictionaryValuesForAuthenticatedConsumers() {
    when(db.queryForList(startsWith("select id,item_value"), eq("EDUCATION")))
        .thenReturn(List.of(Map.of(
            "id", 1L,
            "item_value", "本科",
            "label", "本科",
            "sort_order", 20,
            "enabled", true)));

    var response = controller.values("education", false);

    assertThat(response.data()).singleElement().satisfies(item -> {
      assertThat(item.value()).isEqualTo("本科");
      assertThat(item.enabled()).isTrue();
    });
    verify(permissions, never()).require(Permissions.MASTER_MANAGE);
  }

  @Test
  void requiresMasterPermissionWhenIncludingDisabledValues() {
    when(db.queryForList(startsWith("select id,item_value"), eq("EDUCATION")))
        .thenReturn(List.of());

    controller.values("EDUCATION", true);

    verify(permissions).require(Permissions.MASTER_MANAGE);
  }

  @Test
  void createsCodedDictionaryValueAndWritesAuditLog() {
    when(db.queryForObject("select last_insert_id()", Long.class)).thenReturn(9L);

    var response = controller.create("education",
        new DictionaryController.ValueRequest("研究生", "研究生", 50, true));

    assertThat(response.data()).isEqualTo(9L);
    verify(db).update(startsWith("insert into dictionary_item"),
        eq("EDUCATION"), eq("研究生"), eq("研究生"), eq(50), eq(true));
    verify(audit).log(eq("CREATE_DICTIONARY_VALUE"), eq("DICTIONARY_VALUE"),
        eq(9L), eq(null), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void refusesToChangeStableCodedValue() {
    when(db.queryForList(startsWith("select id,item_value"), eq(3L), eq("EDUCATION")))
        .thenReturn(List.of(Map.of(
            "id", 3L,
            "item_value", "本科",
            "label", "本科",
            "sort_order", 20,
            "enabled", true)));

    assertThatThrownBy(() -> controller.update("EDUCATION", 3L,
        new DictionaryController.ValueRequest("硕士", "大学本科", 20, true)))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("不可修改");
    verify(db, never()).update(startsWith("update dictionary_item"),
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any());
  }

  @Test
  void rejectsUnknownDictionaryType() {
    assertThatThrownBy(() -> controller.values("UNKNOWN", false))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("类型不存在");
  }

  @Test
  void exposesClassPositionButKeepsGenderOutsideDictionaryManagement() {
    when(db.queryForList(startsWith("select id,item_value"), eq("CLASS_POSITION")))
        .thenReturn(List.of());

    assertThat(controller.values("CLASS_POSITION", false).data()).isEmpty();
    assertThatThrownBy(() -> controller.values("GENDER", false))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("类型不存在");
  }
}
