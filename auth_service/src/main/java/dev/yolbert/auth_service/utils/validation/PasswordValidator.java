package dev.yolbert.auth_service.utils.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.regex.Pattern;

/**
 * Validates password strength:
 * - Minimum 10 characters, maximum 128
 * - Only printable ASCII characters (0x20–0x7E) — blocks emojis and non-ASCII
 * - Must contain at least one uppercase letter [A-Z]
 * - Must contain at least one lowercase letter [a-z]
 * - Must contain at least one digit [0-9]
 * - Must contain at least one symbol [!-/ or :-@ or [-` or {-~]
 */
public class PasswordValidator implements ConstraintValidator<ValidPassword, String> {

    // Only printable ASCII, length 10-128
    private static final Pattern PRINTABLE_ASCII = Pattern.compile("^[\\x20-\\x7E]{10,128}$");
    private static final Pattern HAS_UPPER       = Pattern.compile("[A-Z]");
    private static final Pattern HAS_LOWER       = Pattern.compile("[a-z]");
    private static final Pattern HAS_DIGIT       = Pattern.compile("[0-9]");
    private static final Pattern HAS_SYMBOL      = Pattern.compile("[!-/:-@\\[`{-~]");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return false;
        }
        return PRINTABLE_ASCII.matcher(value).matches()
                && HAS_UPPER.matcher(value).find()
                && HAS_LOWER.matcher(value).find()
                && HAS_DIGIT.matcher(value).find()
                && HAS_SYMBOL.matcher(value).find();
    }
}
