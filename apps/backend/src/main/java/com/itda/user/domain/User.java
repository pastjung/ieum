package com.itda.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** User 애그리거트 루트. 상태 변경은 반드시 도메인 메서드를 통해서만 일어난다 (setter 금지). */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Embedded private Email email;

  @Column(nullable = false)
  private String password; // 해시된 값만 저장

  @Column(nullable = false, length = 20)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10)
  private UserRole role;

  @Column(nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @Builder
  private User(Email email, String password, String name, UserRole role) {
    this.email = email;
    this.password = password;
    this.name = name;
    this.role = role;
    this.createdAt = LocalDateTime.now();
  }

  /** 프로필 수정 — 도메인 규칙(이름 길이)을 애그리거트가 스스로 지킨다 */
  public void rename(String newName) {
    if (newName == null || newName.isBlank() || newName.length() > 20) {
      throw new IllegalArgumentException("이름은 1~20자여야 합니다.");
    }
    this.name = newName;
  }
}
