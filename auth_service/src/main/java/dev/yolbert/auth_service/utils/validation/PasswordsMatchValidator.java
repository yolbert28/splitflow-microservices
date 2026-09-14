package dev.yolbert.auth_service.utils.validation;

import dev.yolbert.auth_service.dto.RegisterUserCommand;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class PasswordsMatchValidator implements ConstraintValidator<PasswordsMatch, RegisterUserCommand> {

    @Override
    public boolean isValid(RegisterUserCommand command, ConstraintValidatorContext context) {
        if (command == null) {
            return true;
        }
        String password = command.getPassword();
        String confirm  = command.getConfirmPassword();

        if (password == null || !password.equals(confirm)) {
            // Attach the violation to the confirmPassword field for a clear error message
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                    .addPropertyNode("confirm_password")
                    .addConstraintViolation();
            return false;
        }
        return true;
    }
}
