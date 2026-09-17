package dev.yolbert.auth_service.utils.validation;

import dev.yolbert.auth_service.dto.PasswordResetConfirmCommand;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class PasswordResetConfirmMatchValidator implements ConstraintValidator<PasswordsMatch, PasswordResetConfirmCommand> {

    @Override
    public boolean isValid(PasswordResetConfirmCommand command, ConstraintValidatorContext context) {
        if (command == null) {
            return true;
        }
        String password = command.getNewPassword();
        String confirm  = command.getConfirmNewPassword();

        if (password == null || !password.equals(confirm)) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                    .addPropertyNode("confirm_new_password")
                    .addConstraintViolation();
            return false;
        }
        return true;
    }
}
