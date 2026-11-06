// Oracles: UC-STU-deactivate-student main/3a/4a, BR-evaluation-active-evaluatee,
// BR-evaluation-inactive-history. API stubs prove UI behavior, not server policy.
const student = {
  id: 4,
  username: 'j.smith@abc.edu',
  firstName: 'John',
  lastName: 'Smith',
  email: 'j.smith@abc.edu',
  enabled: true,
  roles: 'student',
  sectionId: 2,
  teamId: 1,
  teamName: 'Team1'
}
const result = (data) => ({ flag: true, code: 200, message: 'OK', data })
const inactive = { ...student, enabled: false }
let listedStudent = student

function openStudent() {
  cy.intercept('POST', '**/students/search*', (req) =>
    req.reply(result({ content: [listedStudent], number: 0, size: 50, totalElements: 1 }))
  ).as('students')
  cy.intercept('GET', '**/students/4', result(student)).as('details')
  cy.visit('/students')
  cy.wait('@students')
  cy.get('[data-cy=student-edit]').first().click()
  cy.wait('@details')
  cy.get('[data-cy=deactivate-open]').click()
}

describe('Dedicated student deactivation', () => {
  beforeEach(() => {
    listedStudent = student
    cy.clock(new Date('2023-08-20T18:00:00Z').getTime(), ['Date'])
    cy.login('b.wei@abc.edu', '123456')
    cy.intercept('POST', '**/students/4/deactivate', (req) => {
      listedStudent = inactive
      req.reply({ delay: 600, body: result({ student: inactive, outcome: 'deactivated' }) })
    }).as('deactivate')
  })

  it('warns about the selected student and consequences before sending; double confirm sends once', () => {
    openStudent()
    cy.get('[data-cy=deactivate-dialog]')
      .should('contain', 'John Smith')
      .and('contain', 'existing login')
      .and('contain', 'Reminders stop')
      .and('contain', 'Team membership')
      .and('contain', 'Historical reports')
    cy.get('@deactivate.all').should('have.length', 0)
    cy.get('[data-cy=deactivate-confirm]').click()
    cy.get('[data-cy=deactivate-confirm]').should('be.disabled').click({ force: true })
    cy.wait('@deactivate').its('request.body').should('be.empty')
    cy.get('@deactivate.all').should('have.length', 1)
    cy.contains('Student deactivated successfully.').should('be.visible')
    cy.get('[data-cy=deactivate-open]').should('not.exist')
    cy.contains('.el-table__body tr', 'John').should('contain', 'Disabled')
  })

  for (const dismissal of ['cancel', 'escape', 'close']) {
    it(`sends no request when the warning is dismissed with ${dismissal} (4a)`, () => {
      openStudent()
      if (dismissal === 'cancel') cy.get('[data-cy=deactivate-cancel]').click()
      else if (dismissal === 'escape') cy.get('body').type('{esc}')
      else cy.get('[data-cy=deactivate-dialog]').find('.el-dialog__headerbtn').click()
      cy.get('[data-cy=deactivate-dialog]').should('not.be.visible')
      cy.get('@deactivate.all').should('have.length', 0)
      cy.get('[data-cy=deactivate-open]').should('be.enabled')
    })
  }

  it('reports an authorized already-inactive outcome (3a)', () => {
    cy.intercept(
      'POST',
      '**/students/4/deactivate',
      result({ student: inactive, outcome: 'already-inactive' })
    ).as('repeat')
    openStudent()
    cy.get('[data-cy=deactivate-confirm]').click()
    cy.wait('@repeat')
    cy.contains('Student is already inactive.').should('be.visible')
    cy.get('[data-cy=deactivate-open]').should('not.exist')
  })

  it('does not save unsaved profile edits with deactivation', () => {
    cy.intercept('PUT', '**/students/4', result(student)).as('profile')
    openStudent()
    cy.get('[data-cy=deactivate-cancel]').click()
    cy.get('.el-form-item').contains('First Name:').parent().find('input').clear().type('Unsaved')
    cy.get('[data-cy=deactivate-open]').click()
    cy.get('[data-cy=deactivate-dialog]')
      .should('contain', 'John Smith')
      .and('not.contain', 'Unsaved')
    cy.get('[data-cy=deactivate-confirm]').click()
    cy.wait('@deactivate')
    cy.get('@profile.all').should('have.length', 0)
  })

  for (const status of [400, 500]) {
    it(`shows no success and reloads current state after a ${status} response`, () => {
      cy.intercept('POST', '**/students/4/deactivate', {
        statusCode: status,
        body: { flag: false, code: status, message: 'Refused' }
      }).as('failed')
      openStudent()
      cy.get('[data-cy=deactivate-confirm]').click()
      cy.wait('@failed')
      cy.wait('@details')
      cy.get('[data-cy=deactivate-error]').should('contain', 'did not confirm')
      cy.contains('Student deactivated successfully.').should('not.exist')
      cy.get('[data-cy=deactivate-confirm]').should('be.enabled')
    })
  }

  it('resolves a lost response by loading status and never retries automatically', () => {
    cy.intercept('POST', '**/students/4/deactivate', { forceNetworkError: true }).as('lost')
    openStudent()
    cy.intercept('GET', '**/students/4', result(inactive)).as('resolved')
    cy.get('[data-cy=deactivate-confirm]').click()
    cy.wait('@resolved')
    cy.get('[data-cy=deactivate-open]').should('not.exist')
    cy.contains('Student deactivated successfully.').should('not.exist')
    cy.get('@lost.all').should('have.length', 1)
  })
})

describe('Inactive evaluation history and eligibility', () => {
  beforeEach(() => {
    cy.clock(new Date('2023-08-20T18:00:00Z').getTime(), ['Date'])
  })

  it('labels inactive historical rows while retaining their scores', () => {
    cy.login('b.wei@abc.edu', '123456')
    const summary = {
      studentId: 4,
      week: '2023-W32',
      firstName: 'John',
      lastName: 'Smith',
      enabled: false,
      averageTotalScore: 54,
      publicComments: ['Historical public'],
      privateComments: ['Historical private'],
      ratingAverages: []
    }
    cy.intercept(
      'GET',
      '**/evaluations/sections/*/week/*',
      result({ sectionName: '2023-2024', week: '2023-W32', peerEvaluationAverages: [summary] })
    ).as('report')
    cy.visit('/section-evaluations')
    cy.wait('@report')
    cy.get('[data-cy=evaluation-inactive]').should('contain', 'Inactive')
    cy.contains('54.00').should('be.visible')
    cy.contains('Historical public').should('be.visible')
  })

  function visitEvaluations(roster, saved) {
    cy.intercept('GET', '**/students/teams/*', result(roster)).as('roster')
    cy.intercept('GET', '**/evaluations/evaluators/*/week/*', result(saved)).as('saved')
    cy.intercept('POST', '**/evaluations/weeks/*/receipt', result(null)).as('receipt')
    cy.visit('/evaluations/submit-evaluations')
    cy.wait(['@roster', '@saved'])
  }
  const entry = (evaluateeId, name, id) => ({
    evaluationId: id,
    evaluatorId: 4,
    evaluateeId,
    evaluateeName: name,
    week: '2023-W32',
    ratings: Array.from({ length: 6 }, (_, index) => ({
      criterionId: index + 1,
      criterion: 'Criterion',
      actualScore: 9
    })),
    publicComment: '',
    privateComment: ''
  })

  it('excludes inactive new evaluatees but keeps a previously saved evaluation editable', () => {
    cy.login('j.smith@abc.edu', '123456')
    const eric = { ...student, id: 5, firstName: 'Eric', lastName: 'Hudson', enabled: false }
    const jerry = { ...student, id: 6, firstName: 'Jerry', lastName: 'Moon', enabled: false }
    visitEvaluations([student, eric, jerry], [entry(5, 'Eric Hudson', 88)])
    cy.get('.el-table__body').should('contain', 'Eric Hudson').and('not.contain', 'Jerry Moon')
    cy.intercept('PUT', '**/evaluations/88', result(entry(5, 'Eric Hudson', 88))).as(
      'updateExisting'
    )
    cy.intercept('POST', '**/evaluations', result(entry(4, 'John Smith', 89))).as('newSelf')
    cy.get('[data-cy=evaluation-save]').click()
    cy.wait(['@updateExisting', '@newSelf'])
  })

  it('keeps unsaved feedback on an existing evaluation when its update fails', () => {
    cy.login('j.smith@abc.edu', '123456')
    visitEvaluations([student], [entry(4, 'John Smith', 88)])
    cy.contains('.el-table__body tr', 'John Smith')
      .find('textarea')
      .first()
      .type('Keep my feedback')
    cy.intercept('PUT', '**/evaluations/88', {
      statusCode: 500,
      body: { flag: false, code: 500, message: 'Try again' }
    }).as('failedUpdate')
    cy.get('[data-cy=evaluation-save]').click()
    cy.wait('@failedUpdate')
    cy.wait(['@roster', '@saved'])
    cy.get('[data-cy=evaluation-save]').should('be.enabled')
    cy.get('[data-cy=evaluation-save-notice]').should('be.visible')
    cy.contains('.el-table__body tr', 'John Smith')
      .find('textarea')
      .first()
      .should('have.value', 'Keep my feedback')
  })

  it('refreshes stale eligibility after partial failure and does not recreate saved rows', () => {
    cy.login('j.smith@abc.edu', '123456')
    const jerry = { ...student, id: 6, firstName: 'Jerry', lastName: 'Moon' }
    visitEvaluations([student, jerry], [])
    let postedSelf = 0
    cy.intercept('POST', '**/evaluations', (req) => {
      if (req.body.evaluateeId === 4) {
        postedSelf++
        req.reply(result(entry(4, 'John Smith', 88)))
      } else
        req.reply({
          statusCode: 400,
          body: { flag: false, code: 400, message: 'Student is inactive' }
        })
    }).as('submit')
    cy.intercept('GET', '**/students/teams/*', result([student, { ...jerry, enabled: false }]))
    cy.intercept('GET', '**/evaluations/evaluators/*/week/*', result([entry(4, 'John Smith', 88)]))
    cy.get('[data-cy=evaluation-save]').click()
    cy.get('[data-cy=evaluation-save-notice]').should('contain', 'Some evaluations were not saved')
    cy.get('.el-table__body').should('contain', 'John Smith').and('not.contain', 'Jerry Moon')
    cy.intercept('PUT', '**/evaluations/88', result(entry(4, 'John Smith', 88))).as('update')
    cy.get('[data-cy=evaluation-save]').click()
    cy.wait('@update').then(() => expect(postedSelf).to.eq(1))
  })
})
