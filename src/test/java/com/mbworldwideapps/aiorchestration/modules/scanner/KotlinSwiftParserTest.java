package com.mbworldwideapps.aiorchestration.modules.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.mbworldwideapps.aiorchestration.modules.scanner.KotlinSwiftParser.Call;
import com.mbworldwideapps.aiorchestration.modules.scanner.KotlinSwiftParser.Declaration;
import com.mbworldwideapps.aiorchestration.modules.scanner.KotlinSwiftParser.Dependency;
import com.mbworldwideapps.aiorchestration.modules.scanner.KotlinSwiftParser.Language;
import com.mbworldwideapps.aiorchestration.modules.scanner.KotlinSwiftParser.ParsedFile;
import org.junit.jupiter.api.Test;

class KotlinSwiftParserTest {

    @Test
    void readsKotlinAndroidDeclarationsDependenciesAndCalls() {
        ParsedFile parsed = KotlinSwiftParser.parse(Language.KOTLIN, """
                package com.example.login

                import javax.inject.Inject

                /* a { brace in a comment */
                @HiltViewModel
                class LoginViewModel @Inject constructor(
                    private val repository: AuthRepository,
                    private val analytics: Analytics,
                ) : ViewModel() {

                    private val _state = MutableStateFlow(LoginState())
                    val greeting = "Hello { not a block }"

                    fun login(user: String, password: String) {
                        viewModelScope.launch {
                            val session = repository.signIn(user, password)
                            analytics.track("login ${session.id}")
                            _state.value = LoginState(done = true)
                        }
                        validate(user)
                    }

                    private fun validate(user: String): Boolean = user.isNotBlank() &&
                        user.length > 2

                    companion object {
                        fun create(): LoginViewModel = TODO()
                    }
                }

                interface AuthApi {
                    @POST("auth/login")
                    suspend fun login(@Body body: LoginBody): Session

                    @GET("/users/{id}")
                    suspend fun user(@Path("id") id: String): User
                }

                @Composable
                fun LoginScreen(viewModel: LoginViewModel = hiltViewModel()) {
                    val state by viewModel.state.collectAsState()
                    Column {
                        LoginForm(onSubmit = { viewModel.login(it.user, it.password) })
                    }
                }

                fun String.masked(): String = "***"
                """);

        assertThat(parsed.packageName()).isEqualTo("com.example.login");
        Declaration viewModel = find(parsed, "com.example.login.LoginViewModel");
        assertThat(viewModel.kind()).isEqualTo("class");
        assertThat(viewModel.role()).isEqualTo("viewmodel");
        assertThat(viewModel.annotations()).containsExactly("HiltViewModel");
        assertThat(viewModel.supertypes()).containsExactly("ViewModel");
        assertThat(viewModel.startLine()).isEqualTo(6);
        assertThat(viewModel.endLine()).isEqualTo(30);
        assertThat(viewModel.dependencies())
                .contains(new Dependency("repository", "AuthRepository", true),
                        new Dependency("analytics", "Analytics", true),
                        new Dependency("_state", "MutableStateFlow", false));

        Declaration login = find(parsed, "com.example.login.LoginViewModel#login");
        assertThat(login.kind()).isEqualTo("method");
        assertThat(login.signature()).isEqualTo("login(String,String)");
        assertThat(login.startLine()).isEqualTo(15);
        assertThat(login.endLine()).isEqualTo(22);
        assertThat(login.calls()).contains(new Call("repository", "signIn"), new Call("analytics", "track"),
                new Call(null, "validate"), new Call("viewModelScope", "launch"), new Call(null, "LoginState"));

        Declaration validate = find(parsed, "com.example.login.LoginViewModel#validate");
        assertThat(validate.returnType()).isEqualTo("Boolean");
        assertThat(validate.endLine()).isEqualTo(25);
        assertThat(validate.calls()).contains(new Call("user", "isNotBlank"));

        assertThat(find(parsed, "com.example.login.LoginViewModel.Companion").kind()).isEqualTo("object");
        assertThat(find(parsed, "com.example.login.LoginViewModel.Companion#create").ownerFqn())
                .isEqualTo("com.example.login.LoginViewModel.Companion");

        Declaration api = find(parsed, "com.example.login.AuthApi");
        assertThat(api.kind()).isEqualTo("interface");
        Declaration apiLogin = find(parsed, "com.example.login.AuthApi#login");
        assertThat(apiLogin.httpCall()).isEqualTo("POST /auth/login");
        assertThat(apiLogin.role()).isEqualTo("client");
        assertThat(apiLogin.signature()).isEqualTo("login(LoginBody)");
        assertThat(apiLogin.endLine()).isEqualTo(apiLogin.startLine() + 1);
        assertThat(find(parsed, "com.example.login.AuthApi#user").httpCall()).isEqualTo("GET /users/{id}");

        Declaration screen = find(parsed, "com.example.login#LoginScreen");
        assertThat(screen.kind()).isEqualTo("function");
        assertThat(screen.role()).isEqualTo("screen");
        assertThat(screen.localTypes()).containsEntry("viewModel", "LoginViewModel");
        assertThat(screen.calls()).contains(new Call(null, "LoginForm"), new Call("viewModel", "login"),
                new Call(null, "Column"));

        Declaration masked = find(parsed, "com.example.login#masked");
        assertThat(masked.receiverType()).isEqualTo("String");
        // Nothing from inside comments or strings becomes a declaration.
        assertThat(parsed.declarations()).extracting(Declaration::name).doesNotContain("not", "a");
    }

    @Test
    void readsSwiftTypesExtensionsInitInjectionAndBodies() {
        ParsedFile parsed = KotlinSwiftParser.parse(Language.SWIFT, """
                import SwiftUI

                protocol AuthServicing {
                    func signIn(user: String) async throws -> Session
                }

                @MainActor
                final class LoginViewModel: ObservableObject {
                    @Published var isLoading = false
                    private let service: AuthServicing
                    private let store = SessionStore.shared

                    init(service: AuthServicing) {
                        self.service = service
                    }

                    func login(user: String) async {
                        let text = "brace } in \\(user) string"
                        if isLoading { return }
                        if let child = self as? ChildViewModel { child.reset() }
                        do {
                            let session = try await service.signIn(user: user)
                            store.save(session)
                        } catch {
                            report(error)
                        }
                    }

                    class func make() -> LoginViewModel { LoginViewModel(service: AuthService()) }
                }

                extension LoginViewModel: Identifiable {
                    func report(_ error: Error) {}
                }

                struct LoginView: View {
                    @StateObject var viewModel: LoginViewModel

                    var body: some View {
                        VStack {
                            Button("Sign in") {
                                Task { await viewModel.login(user: "a") }
                            }
                            ProfileHeader(name: "x")
                        }
                    }
                }
                """);

        assertThat(parsed.packageName()).isEmpty();
        assertThat(find(parsed, "AuthServicing").kind()).isEqualTo("interface");
        Declaration signIn = find(parsed, "AuthServicing#signIn");
        assertThat(signIn.returnType()).isEqualTo("Session");
        assertThat(signIn.endLine()).isEqualTo(signIn.startLine());

        Declaration viewModel = find(parsed, "LoginViewModel");
        assertThat(viewModel.role()).isEqualTo("viewmodel");
        assertThat(viewModel.annotations()).containsExactly("MainActor");
        assertThat(viewModel.dependencies())
                .contains(new Dependency("service", "AuthServicing", true),
                        new Dependency("store", "SessionStore", false));
        // `extension LoginViewModel` in the same file: its members group under the class itself.
        assertThat(parsed.declarations()).noneMatch(declaration -> declaration.kind().equals("extension"));
        assertThat(find(parsed, "LoginViewModel#report").ownerFqn()).isEqualTo("LoginViewModel");
        assertThat(viewModel.supertypes()).containsExactly("ObservableObject", "Identifiable");
        assertThat(find(parsed, "LoginViewModel#make").kind()).isEqualTo("method");
        assertThat(find(parsed, "LoginViewModel#init").signature()).isEqualTo("init(AuthServicing)");

        Declaration login = find(parsed, "LoginViewModel#login");
        assertThat(login.startLine()).isEqualTo(17);
        assertThat(login.endLine()).isEqualTo(27);
        assertThat(login.calls()).contains(new Call("service", "signIn"), new Call("store", "save"),
                new Call(null, "report"));
        assertThat(login.calls()).extracting(Call::name).doesNotContain("isLoading", "user", "ChildViewModel");

        Declaration view = find(parsed, "LoginView");
        assertThat(view.kind()).isEqualTo("struct");
        assertThat(view.role()).isEqualTo("view");
        assertThat(view.dependencies()).contains(new Dependency("viewModel", "LoginViewModel", true));
        Declaration body = find(parsed, "LoginView#body");
        assertThat(body.kind()).isEqualTo("property");
        assertThat(body.calls()).contains(new Call("viewModel", "login"), new Call(null, "ProfileHeader"),
                new Call(null, "VStack"));
    }

    @Test
    void swiftExtensionInItsOwnFileIsATypeNode() {
        ParsedFile parsed = KotlinSwiftParser.parse(Language.SWIFT, """
                extension LoginViewModel: Equatable {
                    static func == (lhs: LoginViewModel, rhs: LoginViewModel) -> Bool { true }
                    var title: String { "Login" }
                }
                """);

        Declaration extension = find(parsed, "LoginViewModel");
        assertThat(extension.kind()).isEqualTo("extension");
        assertThat(extension.supertypes()).containsExactly("Equatable");
        assertThat(parsed.declarations()).extracting(Declaration::fqn)
                .contains("LoginViewModel#==", "LoginViewModel#title");
    }

    @Test
    void kotlinConstructorOnALaterLineStillOpensTheClassBody() {
        ParsedFile parsed = KotlinSwiftParser.parse(Language.KOTLIN, """
                class CameraSource  // converted from Java
                /** Only via the builder. */
                private constructor() {
                    class Builder(context: Context?) {
                        fun build(): CameraSource = CameraSource()
                    }
                }
                """);

        assertThat(find(parsed, "CameraSource").endLine()).isEqualTo(7);
        assertThat(find(parsed, "CameraSource.Builder#build").kind()).isEqualTo("method");
    }

    @Test
    void kotlinExpressionBodyEndingInAStringDoesNotRunIntoTheNextFunction() {
        ParsedFile parsed = KotlinSwiftParser.parse(Language.KOTLIN, """
                object Keys {
                    private fun offerKey() = (session?.userIdHash
                            ?: "") + "CrossSellingOffered"

                    private fun corporateKey() = "Corporate"
                }
                """);

        assertThat(find(parsed, "Keys#offerKey").endLine()).isEqualTo(3);
        assertThat(find(parsed, "Keys#corporateKey").startLine()).isEqualTo(5);
    }

    @Test
    void simpleTypeDropsQualifiersGenericsAndOptionals() {
        assertThat(KotlinSwiftParser.simpleType("com.example.Repo<User>?")).isEqualTo("Repo");
        assertThat(KotlinSwiftParser.simpleType("some View")).isEqualTo("View");
        assertThat(KotlinSwiftParser.simpleType("(Int) -> Unit")).isEmpty();
        assertThat(KotlinSwiftParser.simpleType("[String: Int]")).isEmpty();
        assertThat(KotlinSwiftParser.projectTypeCandidate("String")).isFalse();
        assertThat(KotlinSwiftParser.projectTypeCandidate("AuthRepository")).isTrue();
    }

    private static Declaration find(ParsedFile parsed, String fqn) {
        List<Declaration> matches = parsed.declarations().stream()
                .filter(declaration -> declaration.fqn().equals(fqn))
                .toList();
        assertThat(matches).as("declaration %s in %s", fqn,
                parsed.declarations().stream().map(Declaration::fqn).toList()).hasSize(1);
        return matches.getFirst();
    }
}
