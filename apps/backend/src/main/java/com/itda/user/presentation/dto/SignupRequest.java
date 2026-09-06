package com.itda.user.presentation.dto;

import com.itda.user.application.SignupCommand;
import com.itda.user.domain.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SignupRequest(
    @NotBlank @Email String email,
    @NotBlank @Size(min = 8, max = 64) String password,
    @NotBlank @Size(min = 2, max = 20) String name,
    @NotNull UserRole role) {

  public SignupCommand toCommand() {
    return new SignupCommand(email, password, name, role);
  }
}
