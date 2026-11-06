package team.projectpulse.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import team.projectpulse.user.UserService;

/**
 * FR-SEC-active-account: an otherwise valid token does not outlive its account's active status.
 * Compose with the decoder's signature and time validation; this is not a replacement for either.
 */
@Component
public class ActiveAccountJwtValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error UNAVAILABLE_ACCOUNT =
            new OAuth2Error("invalid_token", "The account is unavailable.", null);

    private final UserService userService;

    public ActiveAccountJwtValidator(UserService userService) {
        this.userService = userService;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        Integer userId = readUserId(token.getClaim("userId"));
        if (userId == null || !this.userService.isAccountEnabled(userId)) {
            return OAuth2TokenValidatorResult.failure(UNAVAILABLE_ACCOUNT);
        }
        return OAuth2TokenValidatorResult.success();
    }

    private static Integer readUserId(Object claim) {
        try {
            int id;
            if (claim instanceof Integer value) {
                id = value;
            } else if (claim instanceof Long value) {
                id = Math.toIntExact(value);
            } else if (claim instanceof String value && value.matches("[1-9][0-9]*")) {
                id = Integer.parseInt(value);
            } else {
                return null;
            }
            return id > 0 ? id : null;
        } catch (ArithmeticException | NumberFormatException ex) {
            return null;
        }
    }
}
