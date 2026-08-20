package com.talent.platform.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmployeeInitialPasswordTest {
  @Test void usesLastSixDigits() {
    assertThat(EmployeeInitialPassword.fromIdCard("123456789012345678"))
        .isEqualTo("345678");
  }

  @Test void normalizesLowercaseXToUppercase() {
    assertThat(EmployeeInitialPassword.fromIdCard("12345678901234567x"))
        .isEqualTo("34567X");
  }

  @Test void rejectsInvalidIdCardFormat() {
    assertThatThrownBy(() -> EmployeeInitialPassword.fromIdCard("123456"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("18位");
  }
}
