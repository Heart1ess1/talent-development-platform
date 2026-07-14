package com.talent.platform.security;
import org.junit.jupiter.api.Test;import static org.assertj.core.api.Assertions.assertThat;
class JwtServiceTest {
  @Test void tokenRoundTrip(){var service=new JwtService("a-development-secret-that-is-longer-than-32-bytes",120);var user=new CurrentUser(7L,"20260001","张三","EMPLOYEE",true);var parsed=service.parse(service.create(user));assertThat(parsed).isEqualTo(user);}
  @Test void securityVersionIsSignedIntoToken(){var service=new JwtService("a-development-secret-that-is-longer-than-32-bytes",120);var user=new CurrentUser(7L,"u","U","ADMIN",false,9,java.util.Set.of("x"),"ALL");var parsed=service.parse(service.create(user));assertThat(parsed.securityVersion()).isEqualTo(9);assertThat(parsed.role()).isEqualTo("ADMIN");}
}
