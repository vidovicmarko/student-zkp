package hr.fer.studentzkp.service

// Student registry verification — validates JMBAG against the actual university's student database.
// Implementations: HTTP API call, LDAP query, CSV file, etc.
interface StudentRegistryService {

    // Verify that a JMBAG exists in the university's student registry.
    // Returns true if valid, false if invalid or not found.
    // Should throw an exception if the registry is unreachable.
    fun isValidStudent(jmbag: String): Boolean
}

// Development stub — always accepts any valid-format JMBAG (10 digits).
// In prod, replace with real integrations (UnizagRegistryService, LDAP, etc).
class StubStudentRegistryService : StudentRegistryService {
    override fun isValidStudent(jmbag: String): Boolean {
        // In dev, we trust the local `students` table seeded by migrations.
        // Real implementation checks against university LDAP/API.
        return true
    }
}
