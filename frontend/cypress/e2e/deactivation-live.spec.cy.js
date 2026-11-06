// Real isolated dev stack: UI confirmation -> committed endpoint -> existing-token rejection.
// Fixture account is restored after the test; no API stubs are used.
describe('Deactivation through the running application', () => {
  const api = 'http://localhost/api/v1'
  let adminToken
  let originalStudent
  let studentToken

  beforeEach(() => {
    cy.viewport(1280, 900)
    cy.clock(new Date('2023-08-20T18:00:00Z').getTime(), ['Date'])
    cy.request({
      method: 'POST',
      url: `${api}/users/login`,
      auth: { username: 'b.wei@abc.edu', password: '123456' }
    }).then(({ body }) => {
      adminToken = body.data.token
    })
    cy.request({
      method: 'POST',
      url: `${api}/users/login`,
      auth: { username: 'w.allen@abc.edu', password: '123456' }
    }).then(({ body }) => {
      studentToken = body.data.token
    })
    cy.then(() =>
      cy.request({ url: `${api}/students/7`, headers: { Authorization: `Bearer ${adminToken}` } })
    ).then(({ body }) => {
      originalStudent = body.data
      expect(originalStudent.enabled).to.eq(true)
    })
    cy.login('b.wei@abc.edu', '123456')
  })

  afterEach(() => {
    if (originalStudent)
      cy.request({
        method: 'PUT',
        url: `${api}/students/7`,
        headers: { Authorization: `Bearer ${adminToken}` },
        body: originalStudent
      })
  })

  it('deactivates after confirmation, retains the team, rejects the old token, and accepts an authorized repeat', () => {
    cy.intercept('POST', '**/students/7/deactivate').as('deactivateLive')
    cy.visit('/students')
    cy.contains('.el-table__body tr', 'Woody').find('[data-cy=student-edit]').click()
    cy.get('[data-cy=deactivate-open]').click()
    cy.get('[data-cy=deactivate-dialog]').should('contain', 'Woody Allen')
    cy.screenshot('student-deactivation-confirmation', { capture: 'viewport' })
    cy.get('[data-cy=deactivate-confirm]').click()
    cy.wait('@deactivateLive').its('response.body.data.outcome').should('eq', 'deactivated')
    cy.get('[data-cy=deactivate-open]').should('not.exist')
    cy.contains('.el-table__body tr', 'Woody').should('contain', 'Disabled')
    cy.then(() =>
      cy.request({ url: `${api}/students/7`, headers: { Authorization: `Bearer ${adminToken}` } })
    ).then(({ body }) => {
      expect(body.data.enabled).to.eq(false)
      expect(body.data.teamId).to.eq(originalStudent.teamId)
    })
    cy.then(() =>
      cy.request({
        url: `${api}/students/7`,
        headers: { Authorization: `Bearer ${studentToken}` },
        failOnStatusCode: false
      })
    )
      .its('status')
      .should('eq', 401)
    cy.then(() =>
      cy.request({
        method: 'POST',
        url: `${api}/students/7/deactivate`,
        headers: { Authorization: `Bearer ${adminToken}` }
      })
    )
      .its('body.data.outcome')
      .should('eq', 'already-inactive')
  })
})
