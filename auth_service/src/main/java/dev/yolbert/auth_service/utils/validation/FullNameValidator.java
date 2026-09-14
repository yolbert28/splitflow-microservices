package dev.yolbert.auth_service.utils.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.regex.Pattern;

/**
 * Validates full_name field:
 * - Only Unicode letters and single spaces between words
 * - Each word must have at least 2 characters (blocks patterns like "A L B E R T O")
 * - Maximum 150 characters
 * - No special characters, symbols, emojis, or digits
 */
public class FullNameValidator implements ConstraintValidator<ValidFullName, String> {

    // Each word: at least 2 Unicode letters. Words separated by a single space.
    private static final Pattern VALID_NAME = Pattern.compile(
            "^\\p{L}{2,}( \\p{L}{2,})*$",
            Pattern.UNICODE_CHARACTER_CLASS
    );

    private static final int MAX_LENGTH = 150;

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return false;
        }
        if (value.length() > MAX_LENGTH) {
            return false;
        }
        return VALID_NAME.matcher(value).matches();
    }
}
