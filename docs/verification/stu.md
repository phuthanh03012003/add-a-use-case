# Student deactivation verification

Scope: UC-STU-deactivate-student and the policy effects implemented from [Student Design](../design/stu.md). This is agent-prepared evidence and review, completed with the student's permission. It does not claim that the student personally read the diff.

## Executed checks

- Full backend command: `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home bash mvnw -q test` — **485 tests passed, zero failures/errors/skips**, on Java 21 with Testcontainers MySQL and Mailpit.
- After adding the instructor regression test and restoring the mutation, `bash mvnw -q -Dtest=StudentAccountStateIntegrationTest test` — **6 passed**. The full run preceded this additional test; it is not claimed as a second full-suite run.
- Frontend `npm run build-only` and ESLint on changed source files passed. Both Cypress specs pass JavaScript syntax checks.
- `npm run build` / `npm run type-check` fail on **18 pre-existing TypeScript diagnostics**. The same check on a temporary checkout of merged `main`, using the same installed dependencies, reports the identical 18 diagnostics; this branch introduces none. These unrelated diagnostics were not silently repaired.
- Feature browser run: **14 passed**. Complete Cypress regression suite: **33 passed, zero failures or skips**, using headless Electron against the isolated dev stack. The live case confirmed account deactivation, preserved team membership, rejected the previously issued token, accepted an authorized repeat, and restored its fixture afterward.
- [Confirmation screenshot](student-deactivation-confirmation.png) was captured from the real running application before confirming the action.
- Browser-test corrections: scoped the profile selector to its dialog; scrolled to clipped report comments; isolated stubbed account actions with a fictitious ID; accounted for Chromium replaying a dropped transport request. The three legacy specs were updated to the current seeded email login and actual heading selector; menu tests use the dev fixture date.

## Design test-list coverage

| Design row | Evidence | Result |
|---|---|---|
| `deactivate-main` | StudentDeactivationIntegrationTest.deactivationPreservesMembershipProfileAndEveryStoredWorkRow; deactivation-live.spec.cy.js | Passed (backend and UI) |
| `deactivate-permitted-callers` | StudentDeactivationIntegrationTest.deactivationPreservesMembershipProfileAndEveryStoredWorkRow (assigned instructor); ownerWithoutSectionAssignmentOrInstructorRoleCanDeactivate; StudentLifecycleSecurityIntegrationTest.owningAdminWithoutSectionAssignmentOrLiteralInstructorRoleCanReadStudent | Passed |
| `deactivate-membership` | StudentDeactivationIntegrationTest.deactivationPreservesMembershipProfileAndEveryStoredWorkRow; unassignedStudentAndRepeatWorkWithoutAnyActiveStudentRemaining | Passed |
| `deactivate-preserves-work` | StudentDeactivationIntegrationTest.deactivationPreservesMembershipProfileAndEveryStoredWorkRow | Passed |
| `deactivate-unauthorized` | StudentLifecycleSecurityIntegrationTest.lifecycleRouteDeniesStudentUnassignedInstructorAndUnrelatedAdmin; StudentDeactivationIntegrationTest.serviceAlsoRejectsCallerOutsideScopeWithoutRelyingOnRouteRule | Passed |
| `deactivate-authorization-order` | StudentLifecycleSecurityIntegrationTest.unauthorizedInactiveTargetDoesNotDiscloseAlreadyInactiveOutcome | Passed |
| `deactivate-invalid-target` | StudentLifecycleSecurityIntegrationTest.invalidTargetFailsClosedAndAnonymousCallerIsUnauthenticated | Passed |
| `deactivate-repeat` | StudentDeactivationIntegrationTest.unassignedStudentAndRepeatWorkWithoutAnyActiveStudentRemaining; deactivation.spec.cy.js already-inactive case | Passed (backend and UI) |
| `deactivate-cancel` | deactivation.spec.cy.js cancel, escape, and close cases (extension 4a) | Passed (Cypress) |
| `deactivate-confirmation-state` | deactivation.spec.cy.js double-confirm, 400/500, and lost-response cases | Passed (Cypress) |
| `deactivate-existing-token` | StudentAccountStateIntegrationTest.currentStatusRejectsAnAlreadyIssuedRealTokenAfterCommittedDeactivation | Passed |
| `deactivate-authentication-regression` | ActiveAccountJwtValidatorTest; StudentAccountStateIntegrationTest.committedInstructorDeactivationRejectsItsRealTokenAndNewLogin; missingOrDeletedAccountIsNotEnabled | Passed |
| `deactivate-profile-bypass` | StudentDeactivationIntegrationTest.profileCannotBypassActionButOrdinaryEditStillWorks; StudentServiceTest.testInstructorCannotDeactivateThroughProfileUpdate | Passed |
| `deactivate-submission-status` | ReminderIntegrationTest.testDeactivationRemovesReminderRecipientAndRequiredEvaluatee; SubmissionStatusServiceIntegrationTest.testAnEvaluationOfADeactivatedTeammateIsNotRequired | Passed |
| `deactivate-new-evaluation` | DeactivationEvaluationIntegrationTest.newEvaluationOfInactiveTeammateIsRejectedWhileActiveTeammateStillWorks | Passed |
| `deactivate-evaluation-ordering` | DeactivationEvaluationIntegrationTest.newEvaluationAndDeactivationAreOrderedThroughCommit (both parameter values) | Passed |
| `deactivate-evaluation-form` | deactivation.spec.cy.js inactive entries, partial-failure recovery, and failed-PUT feedback cases | Passed (Cypress) |
| `deactivate-report-inclusion` | DeactivationEvaluationIntegrationTest.historicalRowsDependOnReceivedWeekAndKeepInactiveEvaluatorsScoresAndComments | Passed |
| `deactivate-report-calculation` | DeactivationEvaluationIntegrationTest.historicalRowsDependOnReceivedWeekAndKeepInactiveEvaluatorsScoresAndComments; existing EvaluationIntegrationTest visibility checks | Passed |
| `deactivate-report-label` | deactivation.spec.cy.js inactive historical row case | Passed (Cypress) |
| `deactivate-existing-evaluation-edit` | DeactivationEvaluationIntegrationTest.existingEvaluationOfInactiveTeammateRemainsEditableOnlyInsideItsWindow | Passed |

## Tests kept and oracle sources

The list names each new or materially changed test method. Existing regression suites were retained and run, not represented as newly authored tests. Parameterized cases share the named method's specification oracle.

### ActiveAccountJwtValidatorTest

- `activeAccountAcceptsSupportedSignedIdRepresentations` — FR-SEC-active-account; deactivate-authentication-regression.
- `malformedOrOutOfRangeIdFailsClosedWithoutAccountLookup` — deactivate-authentication-regression: signed userId parsing.
- `missingIdFailsClosed` — deactivate-authentication-regression: absent signed identity.
- `inactiveOrMissingAccountRejectsTokenWithoutDisclosingWhich` — FR-SEC-active-account; fail-closed authentication contract.
- `realSignedTokenRemainsSubjectToCurrentAccountStatus` — FR-SEC-active-account; extension 6a.
- `activeAccountDoesNotMakeAnExpiredTokenValid` — FR-SEC-authentication; retained JWT expiry contract.
- `activeAccountCheckDoesNotReplaceSignatureVerification` — FR-SEC-authentication; retained JWT signature contract.

### StudentAccountStateIntegrationTest

- `currentStatusRejectsAnAlreadyIssuedRealTokenAfterCommittedDeactivation` — extension 6a; FR-SEC-active-account; real committed endpoint request.
- `committedInstructorDeactivationRejectsItsRealTokenAndNewLogin` — FR-SEC-active-account explicitly covers instructor accounts.
- `accountLookupSeesCommittedStateInsteadOfCallersOlderSnapshot` — FR-SEC-active-account current-status boundary.
- `lockingLookupRefreshesAnAlreadyManagedStudent` — BR-evaluation-active-evaluatee; shared locking/refresh design.
- `studentLockIsHeldUntilCommitAndWaitingReaderGetsCommittedState` — BR-evaluation-active-evaluatee; lock retention through commit.
- `missingOrDeletedAccountIsNotEnabled` — deactivate-authentication-regression; fail-closed identity.

### StudentLifecycleSecurityIntegrationTest

- `owningAdminWithoutSectionAssignmentOrLiteralInstructorRoleCanReadStudent` — BR-student-lifecycle; BR-section-scoped-access.
- `lifecycleRouteDeniesStudentUnassignedInstructorAndUnrelatedAdmin` — extension 2a; FR-SEC-authorization.
- `unauthorizedInactiveTargetDoesNotDiscloseAlreadyInactiveOutcome` — extension 2a; authorization before inactive outcome.
- `invalidTargetFailsClosedAndAnonymousCallerIsUnauthenticated` — FR-SEC-deny-unauthorized; fail-closed API contract.

### StudentDeactivationIntegrationTest

- `deactivationPreservesMembershipProfileAndEveryStoredWorkRow` — steps 5-6; BR-student-lifecycle; BR-student-inactive-team-membership.
- `ownerWithoutSectionAssignmentOrInstructorRoleCanDeactivate` — BR-student-lifecycle; BR-section-scoped-access.
- `unassignedStudentAndRepeatWorkWithoutAnyActiveStudentRemaining` — extension 3a; selected-student precondition; BR-student-inactive-team-membership.
- `serviceAlsoRejectsCallerOutsideScopeWithoutRelyingOnRouteRule` — extension 2a; two authorization enforcement points.
- `profileCannotBypassActionButOrdinaryEditStillWorks` — step 4; dedicated-action API contract.

### DeactivationEvaluationIntegrationTest

- `newEvaluationOfInactiveTeammateIsRejectedWhileActiveTeammateStillWorks` — BR-evaluation-active-evaluatee; UC-EVA-submit-evaluation 9a.
- `historicalRowsDependOnReceivedWeekAndKeepInactiveEvaluatorsScoresAndComments` — BR-evaluation-inactive-history; section-report averaging algorithm, (58+50)/2=54.
- `existingEvaluationOfInactiveTeammateRemainsEditableOnlyInsideItsWindow` — BR-evaluation-editable-until-close; BR-evaluation-submission-window.
- `newEvaluationAndDeactivationAreOrderedThroughCommit` — BR-evaluation-active-evaluatee at save time; both transaction orders.

### StudentServiceTest

- `testInstructorCannotDeactivateThroughProfileUpdate` — step 4; dedicated-action API contract.

### EvaluationServiceTest

- `testAddPeerEvaluationSuccess` — active-evaluatee eligibility and existing successful creation; updated only to supply the new lock dependency.

### ReminderIntegrationTest

- `testDeactivationRemovesReminderRecipientAndRequiredEvaluatee` — BR-submission-owed; inactive student not emailed and not a required evaluatee.

### Cypress cases (all 14 passed)

- warning and double-confirm case — steps 4-6; duplicate confirmation contract.
- cancel dismissal case — extension 4a.
- escape dismissal case — extension 4a.
- close dismissal case — extension 4a.
- already-inactive case — extension 3a.
- unsaved-profile case — dedicated-action API contract.
- 400 response case — step 6; no success on a refused request.
- 500 response case — step 6; no success on a failed request.
- lost-response case — deactivate-confirmation-state; refresh before retry.
- inactive-history label case — BR-evaluation-inactive-history.
- inactive new entries and retained existing evaluation case — BR-evaluation-active-evaluatee; existing-edit rules.
- failed stored-update feedback case — deactivate-evaluation-form; preserve user input during recovery.
- partial-save eligibility recovery case — UC-EVA-submit-evaluation 9a; retain server IDs and do not duplicate creates.
- live stack case in deactivation-live.spec.cy.js — steps 4-6, extension 3a and 6a; BR-student-inactive-team-membership.

## Tests rewritten

- Replaced `StudentServiceTest.testInstructorUpdatesStudent`, which endorsed generic profile deactivation, with `testInstructorCannotDeactivateThroughProfileUpdate`. Its oracle now comes from the dedicated-action contract; it also asserts rejection happens before profile mutation.
- Adapted the existing evaluation creation test to supply the shared student lock dependency; its success expectation was retained.
- Isolated historical report fixtures from pre-existing seed evaluations before asserting the independently specified 54-point average. Rollback tests explicitly flush the simulated completed request before later refresh checks; committed-state tests use actual independent transactions.
- The failed-PUT feedback Cypress assertion waits for recovery GETs and the enabled save button before inspecting the retained text, so the old implementation cannot pass before recovery finishes.

## Code deliberately broken

The least-trusted test was `StudentAccountStateIntegrationTest.lockingLookupRefreshesAnAlreadyManagedStudent`.
One production line was temporarily changed from `entityManager.refresh(student, PESSIMISTIC_WRITE)` to `entityManager.lock(student, PESSIMISTIC_WRITE)`. The row stayed locked but its managed state was stale.
The targeted run went red: **1 test, 1 assertion failure, 0 errors**, with “Expecting value to be false but was true.” The source was restored automatically, the Git diff confirmed no production mutation remained, and the restored six-test class passed.

## Fresh-context agent review

A separate agent read the build diff, design, use case, rules, and tests without the design conversation.

- **P2, fixed in 4114aef:** failed existing-evaluation updates lost typed feedback during recovery. Recovery now retains editable fields while using refreshed server identity; a passing Cypress case covers the failure path.
- **Coverage, fixed in af60acc:** a real instructor token is now checked before/after committed deactivation and subsequent password login is refused.
- **Coverage, source fixed in 4114aef:** stubbed and live Cypress flows now assert the refreshed parent-table Disabled status. The browser assertions passed.
- **Test quality, source fixed in 4114aef:** feedback preservation is asserted after asynchronous recovery completes. The browser assertions passed.
- No additional concrete security/correctness defect was identified in the static review. The agent review does not claim the student personally read the diff. A [labeled inline agent comment](https://github.com/phuthanh03012003/add-a-use-case/pull/3#discussion_r4488881296) records the resolved feedback-loss issue.

## Verification limits

The frontend's full TypeScript build still has 18 unchanged baseline diagnostics; the production bundle and changed-source lint pass. No deployment, production-load benchmark, permanent token revocation, or cancellation of already-authenticated in-flight requests is claimed. Reactivation remains the documented legacy path. The review is explicitly agent-written, with permission; it is not evidence of the student's personal understanding.
