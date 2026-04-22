package hr.fer.studentzkp.repository

import hr.fer.studentzkp.model.Student
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface StudentRepository : JpaRepository<Student, UUID> {
    fun findByStudentId(studentId: String): Student?
}
