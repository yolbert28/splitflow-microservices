package dev.yolbert.auth_service.utils.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = PhotoUrlValidator.class)
@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidPhotoUrl {

    String message() default "La URL de la foto de perfil debe comenzar con https:// y tener como máximo 2048 caracteres.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
