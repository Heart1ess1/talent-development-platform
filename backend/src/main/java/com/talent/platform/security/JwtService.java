package com.talent.platform.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {
  private final SecretKey key; private final long expirationMinutes;
  public JwtService(@Value("${app.jwt.secret}") String secret, @Value("${app.jwt.expiration-minutes}") long expirationMinutes) {
    this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)); this.expirationMinutes = expirationMinutes;
  }
  public String create(CurrentUser u) { Instant now=Instant.now(); return Jwts.builder().subject(u.username()).claim("uid",u.id()).claim("name",u.displayName()).claim("role",u.role()).claim("mustChange",u.mustChangePassword()).claim("sv",u.securityVersion()).issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(expirationMinutes*60))).signWith(key).compact(); }
  public CurrentUser parse(String token) { var c=Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();Number sv=c.get("sv",Number.class);return new CurrentUser(c.get("uid",Long.class),c.getSubject(),c.get("name",String.class),c.get("role",String.class),Boolean.TRUE.equals(c.get("mustChange",Boolean.class)),sv==null?0:sv.intValue(),java.util.Set.of(),"SELF"); }
}
