import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtPsiFactory
import java.io.File

// Parses Kotlin files with the real compiler front-end and reports syntax errors only.
fun main(args: Array<String>) {
    val disposable = Disposer.newDisposable()
    val env = KotlinCoreEnvironment.createForProduction(disposable, CompilerConfiguration(), EnvironmentConfigFiles.JVM_CONFIG_FILES)
    val factory = KtPsiFactory(env.project)
    var errors = 0; var files = 0
    for (root in args) File(root).walk().filter { it.extension == "kt" || it.extension == "kts" }.forEach { f ->
        files++
        val text = f.readText().replace("\r\n", "\n")
        val kt = factory.createFile(f.name, text)
        PsiTreeUtil.collectElementsOfType(kt, PsiErrorElement::class.java).forEach { e ->
            errors++
            val line = text.substring(0, e.textOffset).count { it == '\n' } + 1
            println("${f.path}:$line: ${e.errorDescription}")
        }
    }
    println("$files files parsed, $errors syntax errors")
    Disposer.dispose(disposable)
}
