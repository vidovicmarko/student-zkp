package hr.fer.studentzkp.service

import hr.fer.studentzkp.repository.StudentRepository

// Bean creation lives in StudentRegistryConfig — profile-aware selection
// between this and StubStudentRegistryService. Don't add @Service here or
// Spring will see two beans for the same type.
class UnizagStudentRegistryService(
    private val studentRepository: StudentRepository,
) : StudentRegistryService {

    override fun isValidStudent(jmbag: String): Boolean {
        return studentRepository.findByStudentId(jmbag) != null
    }
}
