package dev.yolbert.auth_service.validation;

import dev.yolbert.auth_service.utils.validation.PhotoUrlValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PhotoUrlValidatorTest {

    private PhotoUrlValidator validator;

    @BeforeEach
    void setUp() {
        validator = new PhotoUrlValidator();
    }

    @Test
    void nullUrl_isValid() {
        assertTrue(validator.isValid(null, null));
    }

    @Test
    void validHttpsUrl_isValid() {
        assertTrue(validator.isValid("https://example.com/avatar.jpg", null));
    }

    @Test
    void httpUrl_isInvalid() {
        assertFalse(validator.isValid("http://example.com/avatar.jpg", null));
    }

    @Test
    void invalidScheme_isInvalid() {
        assertFalse(validator.isValid("ftp://example.com/avatar.jpg", null));
        assertFalse(validator.isValid("javascript:alert(1)", null));
    }

    @Test
    void tooLongUrl_isInvalid() {
        String longUrl = "https://example.com/" + "a".repeat(2045);
        assertFalse(validator.isValid(longUrl, null));
    }
}
