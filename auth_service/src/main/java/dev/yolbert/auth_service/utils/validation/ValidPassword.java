package dev.yolbert.auth_service.utils.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = PasswordValidator.class)
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidPassword {

    String message() default "La contraseña debe tener al menos 10 caracteres e incluir mayúsculas, minúsculas, números y símbolos.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
