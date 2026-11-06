package team.projectpulse.student;

import org.springframework.data.jpa.domain.Specification;
import team.projectpulse.section.Section;

public class StudentSpecs {

    public static Specification<Student> hasId(Integer id) {
        return (root, query, cb) -> cb.equal(root.get("id"), id);
    }

    /** BR-student-lifecycle and BR-section-scoped-access; scope never comes from request fields. */
    public static Specification<Student> managedBy(Integer callerId, boolean admin, boolean instructor) {
        return (root, query, cb) -> {
            if (callerId == null || (!admin && !instructor)) return cb.disjunction();
            var owner = admin
                    ? cb.equal(root.get("section").get("course").get("courseAdmin").get("id"), callerId)
                    : cb.disjunction();
            if (!instructor) return owner;
            var assigned = query.subquery(Integer.class);
            var section = assigned.from(Section.class);
            assigned.select(cb.literal(1)).where(
                    cb.equal(section.get("sectionId"), root.get("section").get("sectionId")),
                    cb.equal(section.join("instructors").get("id"), callerId));
            return cb.or(owner, cb.exists(assigned));
        };
    }

    public static Specification<Student> containsFirstName(String providedFirstName) {
        return (root, query, criteriaBuilder) ->
                criteriaBuilder.like(criteriaBuilder.lower(root.get("firstName")), "%" + providedFirstName.toLowerCase() + "%");
    }

    public static Specification<Student> containsLastName(String providedLastName) {
        return (root, query, criteriaBuilder) ->
                criteriaBuilder.like(criteriaBuilder.lower(root.get("lastName")), "%" + providedLastName.toLowerCase() + "%");
    }

    public static Specification<Student> hasEmail(String providedEmail) {
        return (root, query, criteriaBuilder) ->
                criteriaBuilder.equal(criteriaBuilder.lower(root.get("email")), providedEmail.toLowerCase());
    }

    public static Specification<Student> hasTeamName(String providedTeamName) {
        return (root, query, criteriaBuilder) ->
                criteriaBuilder.equal(criteriaBuilder.lower(root.get("team").get("teamName")), providedTeamName.toLowerCase());
    }

    public static Specification<Student> hasTeamId(String providedTeamId) {
        return (root, query, criteriaBuilder) ->
                criteriaBuilder.equal(root.get("team").get("teamId"), providedTeamId);
    }

    public static Specification<Student> hasSectionName(String providedSectionName) {
        return (root, query, criteriaBuilder) ->
                criteriaBuilder.equal(criteriaBuilder.lower(root.get("section").get("sectionName")), providedSectionName.toLowerCase());
    }

    public static Specification<Student> hasSectionId(String providedSectionId) {
        return (root, query, criteriaBuilder) ->
                criteriaBuilder.equal(root.get("section").get("sectionId"), providedSectionId);
    }

    public static Specification<Student> belongsToSection(Integer providedSectionId) {
        return (root, query, criteriaBuilder) ->
                criteriaBuilder.equal(root.get("section").get("sectionId"), providedSectionId);
    }

}
