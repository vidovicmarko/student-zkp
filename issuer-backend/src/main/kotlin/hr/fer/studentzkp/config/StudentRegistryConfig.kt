package hr.fer.studentzkp.config

import hr.fer.studentzkp.repository.StudentRepository
import hr.fer.studentzkp.service.StubStudentRegistryService
import hr.fer.studentzkp.service.StudentRegistryService
import hr.fer.studentzkp.service.UnizagStudentRegistryService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

@Configuration
class StudentRegistryConfig {

    @Bean
    @Profile("!test")
    fun studentRegistryService(studentRepository: StudentRepository): StudentRegistryService {
        // Production: validate JMBAGs against seeded students table.
        // For integration with actual Unizag LDAP/API, wrap UnizagStudentRegistryService
        // to delegate to their system instead of StudentRepository.
        return UnizagStudentRegistryService(studentRepository)
    }

    @Bean
    @Profile("test")
    fun stubStudentRegistryService(): StudentRegistryService {
        // Test profile: accept all valid-format JMBAGs without database lookup.
        return StubStudentRegistryService()
    }
}
