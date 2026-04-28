package hr.fer.studentzkp.service

import hr.fer.studentzkp.repository.StudentRepository
import org.springframework.stereotype.Service

@Service
class UnizagStudentRegistryService(
    private val studentRepository: StudentRepository,
) : StudentRegistryService {

    override fun isValidStudent(jmbag: String): Boolean {
        return studentRepository.findByStudentId(jmbag) != null
    }
}
