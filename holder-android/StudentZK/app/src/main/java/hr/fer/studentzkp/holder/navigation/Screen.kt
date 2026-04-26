package hr.fer.studentzkp.holder.navigation

sealed class Screen(val route: String) {
    object Wallet : Screen("wallet")
    object Scan : Screen("scan")
    object Settings : Screen("settings")
    object CredentialDetail : Screen("credential_detail/{credentialId}") {
        fun createRoute(credentialId: String) = "credential_detail/$credentialId"
    }
    object VerifyResult : Screen("verify_result")
}
