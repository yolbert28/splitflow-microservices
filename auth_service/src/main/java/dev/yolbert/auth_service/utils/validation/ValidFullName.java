package dev.yolbert.auth_service.utils.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = FullNameValidator.class)
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidFullName {

    String message() default "El nombre completo solo puede contener letras y espacios, y cada palabra debe tener al menos 2 caracteres.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
