package dev.yolbert.auth_service.utils.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.net.URI;

public class PhotoUrlValidator implements ConstraintValidator<ValidPhotoUrl, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }

        if (value.length() > 2048) {
            return false;
        }

        try {
            URI uri = new URI(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null;
        } catch (Exception e) {
            return false;
        }
    }
}
