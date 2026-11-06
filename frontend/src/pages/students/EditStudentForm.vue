<template>
  <el-form
    ref="studentForm"
    :model="studentBeingEdited"
    :rules="rules"
    label-width="110px"
    style="padding-right: 30px"
    label-position="left"
  >
    <el-form-item label="Id:">
      <el-input v-model="studentBeingEdited.id" disabled></el-input>
    </el-form-item>
    <el-form-item label="Username:" prop="username">
      <el-input v-model="studentBeingEdited.username"></el-input>
    </el-form-item>
    <el-form-item label="Status:" prop="enabled">
      <el-tag v-if="currentStudent?.enabled" type="success" data-cy="student-current-status"
        >Active</el-tag
      >
      <el-switch
        v-else-if="currentStudent"
        v-model="studentBeingEdited.enabled"
        inline-prompt
        active-text="Enabled"
        inactive-text="Disabled"
        style="--el-switch-on-color: #13ce66; --el-switch-off-color: #ff4949"
      />
    </el-form-item>
    <el-form-item label="First Name:" prop="firstName">
      <el-input v-model="studentBeingEdited.firstName"></el-input>
    </el-form-item>
    <el-form-item label="Last Name:" prop="lastName">
      <el-input v-model="studentBeingEdited.lastName"></el-input>
    </el-form-item>
    <el-form-item label="Email:" prop="email">
      <el-input v-model="studentBeingEdited.email"></el-input>
    </el-form-item>
    <el-form-item label="Team:" prop="teamName">
      <el-input v-model="studentBeingEdited.teamName" disabled></el-input>
    </el-form-item>
    <el-alert
      v-if="deactivationError"
      :title="deactivationError"
      type="error"
      :closable="false"
      data-cy="deactivate-error"
    />
    <el-button v-if="statusUncertain" @click="refreshStatus" data-cy="deactivate-reload"
      >Reload status</el-button
    >
    <div style="display: flex; justify-content: end; gap: 8px">
      <el-button
        v-if="currentStudent?.enabled"
        type="danger"
        plain
        data-cy="deactivate-open"
        :disabled="deactivating || statusUncertain"
        @click="warningVisible = true"
        >Deactivate student</el-button
      >
      <el-button :disabled="deactivating" @click="$emit('close-dialog')"> Cancel </el-button>
      <el-button
        type="primary"
        :disabled="deactivating || statusUncertain"
        @click="updateExistingStudent()"
      >
        Confirm
      </el-button>
    </div>
  </el-form>
  <el-dialog
    v-model="warningVisible"
    title="Deactivate student?"
    width="520px"
    append-to-body
    data-cy="deactivate-dialog"
    :close-on-click-modal="!deactivating"
    :close-on-press-escape="!deactivating"
    :show-close="!deactivating"
  >
    <p>
      Deactivate <strong>{{ currentStudent?.firstName }} {{ currentStudent?.lastName }}</strong
      >?
    </p>
    <ul>
      <li>
        Access ends immediately, including through an existing login. The student cannot submit
        activity reports or peer evaluations.
      </li>
      <li>
        Reminders stop, and teammates cannot submit new evaluations of this student, including for
        earlier weeks.
      </li>
      <li>Team membership, the account, and all previously submitted work are retained.</li>
      <li>
        Historical reports keep weeks with received evaluations, marked inactive; weeks without
        received evaluations omit this student.
      </li>
      <li>
        The account can be reactivated later. Separate team membership changes remain in effect.
      </li>
    </ul>
    <template #footer>
      <el-button
        :disabled="deactivating"
        @click="warningVisible = false"
        data-cy="deactivate-cancel"
        >Cancel</el-button
      >
      <el-button
        type="danger"
        :loading="deactivating"
        :disabled="deactivating || statusUncertain"
        @click="confirmDeactivation"
        data-cy="deactivate-confirm"
        >Deactivate student</el-button
      >
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ElMessage } from 'element-plus'
import type { Student } from '@/apis/student/types'
import type { FormInstance } from 'element-plus'
import { findStudentById, updateStudent, deactivateStudent } from '@/apis/student'
import { ref, onMounted } from 'vue'

const { studentId } = defineProps(['studentId'])
const emit = defineEmits(['close-dialog', 'student-deactivated'])

// Note that the structure of the userData is NOT the same as the User interface since the roles are stored as an array
const studentBeingEdited = ref<Student>({
  // Data of the user being added or edited
  id: NaN,
  username: '',
  enabled: false,
  firstName: '',
  lastName: '',
  email: ''
})

const currentStudent = ref<Student>()
const warningVisible = ref(false)
const deactivating = ref(false)
const statusUncertain = ref(false)
const deactivationError = ref('')

const studentForm = ref<FormInstance>() // Form reference

// Validation rules
const rules = {
  username: [
    { required: true, message: 'Please input your email as your username', trigger: 'blur' },
    {
      pattern: /^[^\s@]+@[^\s@]+\.[^\s@]+$/,
      message: 'Enter your email as username.',
      trigger: 'blur'
    }
  ],
  enabled: [{ required: true, message: 'Please select the status of the user.', trigger: 'blur' }],
  firstName: [
    { required: true, message: 'Please provide the first name of the user.', trigger: 'blur' }
  ],
  lastName: [
    { required: true, message: 'Please provide the last name of the user.', trigger: 'blur' }
  ],
  email: [
    { required: true, message: 'Please provide the email of the user.', trigger: 'blur' },
    { type: 'email', message: 'Please provide a valid email.', trigger: 'blur' }
  ]
}

async function loadStudent(studentId: number) {
  // Load the user to be edited
  const result = await findStudentById(studentId)
  currentStudent.value = result.data

  studentBeingEdited.value.id = result.data.id as number
  studentBeingEdited.value.username = result.data.username
  studentBeingEdited.value.enabled = result.data.enabled
  studentBeingEdited.value.firstName = result.data.firstName
  studentBeingEdited.value.lastName = result.data.lastName
  studentBeingEdited.value.email = result.data.email
  studentBeingEdited.value.teamName = result.data.teamName
}

onMounted(() => {
  loadStudent(studentId) // Load the artifact to be edited
})

async function refreshStatus() {
  try {
    await loadStudent(studentId)
    statusUncertain.value = false
    if (!currentStudent.value?.enabled) warningVisible.value = false
  } catch {
    statusUncertain.value = true
  }
}

async function confirmDeactivation() {
  if (deactivating.value || statusUncertain.value || !currentStudent.value?.enabled) return
  deactivating.value = true
  deactivationError.value = ''
  try {
    const result = await deactivateStudent(studentId)
    currentStudent.value = result.data.student
    studentBeingEdited.value.enabled = result.data.student.enabled
    warningVisible.value = false
    emit('student-deactivated')
    if (result.data.outcome === 'already-inactive') ElMessage.info('Student is already inactive.')
    else ElMessage.success('Student deactivated successfully.')
  } catch {
    deactivationError.value =
      'The request did not confirm deactivation. Check the current status before trying again.'
    statusUncertain.value = true
    await refreshStatus()
    emit('student-deactivated')
  } finally {
    deactivating.value = false
  }
}

async function updateExistingStudent() {
  if (deactivating.value || statusUncertain.value) return
  await studentForm.value!.validate() // Validate the form

  const updatedStudent: Student = {
    id: studentBeingEdited.value.id,
    username: studentBeingEdited.value.username,
    enabled: studentBeingEdited.value.enabled,
    firstName: studentBeingEdited.value.firstName,
    lastName: studentBeingEdited.value.lastName,
    email: studentBeingEdited.value.email
  }
  // Call the API to update the user
  await updateStudent(updatedStudent)

  ElMessage.success('Student updated successfully')
  emit('close-dialog')
}
</script>

<style scoped lang="scss"></style>
