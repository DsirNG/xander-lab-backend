package com.xander.lab.identity.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ResetPasswordRequest {
    @NotBlank @Email private String email;
    @NotBlank private String code;
    @NotBlank @Size(min = 6, max = 72) private String newPassword;
}
