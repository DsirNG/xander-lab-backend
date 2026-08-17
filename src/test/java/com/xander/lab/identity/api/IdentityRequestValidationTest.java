package com.xander.lab.identity.api;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityRequestValidationTest {
    private static jakarta.validation.ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @Test
    void loginRequiresTypeAndAccount() {
        LoginRequest request = new LoginRequest();
        request.setType("");
        request.setAccount("");

        assertThat(validator.validate(request)).hasSize(2);
    }

    @Test
    void refreshRequiresToken() {
        RefreshTokenRequest request = new RefreshTokenRequest();

        assertThat(validator.validate(request)).hasSize(1);
    }

    @Test
    void registrationRejectsInvalidEmailShortPasswordAndBlankName() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("not-an-email");
        request.setPassword("123");
        request.setName("");

        assertThat(validator.validate(request)).hasSize(3);
    }

    @Test
    void wechatLoginRequiresCode() {
        WechatLoginRequest request = new WechatLoginRequest();

        assertThat(validator.validate(request)).hasSize(1);
    }

    @Test
    void validRegistrationPassesValidation() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("alice@example.com");
        request.setPassword("secret12");
        request.setName("Alice");

        assertThat(validator.validate(request)).isEmpty();
    }
}
