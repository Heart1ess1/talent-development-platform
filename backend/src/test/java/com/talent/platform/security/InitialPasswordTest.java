package com.talent.platform.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InitialPasswordTest {
  @Test void returnsLastSixCharactersAndPreservesLeadingZeroes() {
    assertThat(InitialPassword.fromEmployeeNo("LWPQ00000577")).isEqualTo("000577");
  }

  @Test void trimsWhitespaceAroundEmployeeNumber() {
    assertThat(InitialPassword.fromEmployeeNo("  010200009440  ")).isEqualTo("009440");
  }

  @Test void keepsEmployeeNumbersShorterThanSixCharactersIntact() {
    assertThat(InitialPassword.fromEmployeeNo("00123")).isEqualTo("00123");
  }

  @Test void rejectsBlankEmployeeNumbers() {
    assertThatThrownBy(() -> InitialPassword.fromEmployeeNo("  "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不能为空");
  }
}
