package dev.yolbert.auth_service.utils.validation;

import dev.yolbert.auth_service.dto.ChangePasswordCommand;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class ChangePasswordMatchValidator implements ConstraintValidator<PasswordsMatch, ChangePasswordCommand> {

    @Override
    public boolean isValid(ChangePasswordCommand command, ConstraintValidatorContext context) {
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
