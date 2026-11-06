package team.projectpulse.security.authorizationmanagers;

import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;
import team.projectpulse.student.StudentSecurityService;

import java.util.function.Supplier;

@Component
public class StudentLifecycleAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {
    private final StudentSecurityService studentSecurityService;

    public StudentLifecycleAuthorizationManager(StudentSecurityService studentSecurityService) {
        this.studentSecurityService = studentSecurityService;
    }

    @Override
    public AuthorizationDecision authorize(Supplier<? extends Authentication> authentication,
                                           RequestAuthorizationContext context) {
        return new AuthorizationDecision(this.studentSecurityService.canManageStudent(
                PathVariables.readId(context, "studentId")));
    }
}
