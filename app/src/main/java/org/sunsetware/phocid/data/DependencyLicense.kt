package org.sunsetware.phocid.data

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.sunsetware.phocid.DEPENDENCY_INFOS_FILE_NAME
import org.sunsetware.phocid.LICENSE_MAPPINGS_FILE_NAME
import org.sunsetware.phocid.utils.CaseInsensitiveMap

@Immutable
@Serializable
data class DependencyInfo(
    val project: String,
    val description: String? = null,
    val version: String? = null,
    val developers: List<String>,
    val url: String? = null,
    val licenses: List<License>? = null,
)

@Immutable
@Serializable
data class License(val license: String, @SerialName("license_url") val licenseUrl: String)

@Serializable
data class LicenseMappings(
    val projectMappings: CaseInsensitiveMap<List<String>>,
    val urlMappings: CaseInsensitiveMap<String>,
    val otherMappings: List<Pair<DependencyInfo, List<String>>>,
)

@Stable
inline fun listDependencies(
    crossinline readFile: (String) -> String
): List<Pair<DependencyInfo, List<String>>> {
    @Suppress("JSON_FORMAT_REDUNDANT")
    val dependencyInfos = Json {
        ignoreUnknownKeys = true
    }.decodeFromString<List<DependencyInfo>>(readFile(DEPENDENCY_INFOS_FILE_NAME))
    val licenseMappings =
        Json.decodeFromString<LicenseMappings>(readFile(LICENSE_MAPPINGS_FILE_NAME))
    val licenseTexts =
        (licenseMappings.projectMappings.values.flatMap { it } +
                licenseMappings.urlMappings.values +
                licenseMappings.otherMappings.flatMap { it.second })
            .distinct()
            .associateWith { readFile(it) }
    return dependencyInfos.map { dependency ->
        val licenseNames =
            licenseMappings.projectMappings[dependency.project]
                ?: dependency.licenses!!.map {
                    requireNotNull(licenseMappings.urlMappings[it.licenseUrl]) {
                        "No license name found for ${dependency.project}"
                    }
                }
        Pair(
            dependency,
            licenseNames.map {
                requireNotNull(licenseTexts[it]) { "No license text found for $it" }
            },
        )
    } +
        licenseMappings.otherMappings.map { (dependency, licenses) ->
            dependency to
                licenses.map {
                    requireNotNull(licenseTexts[it]) { "No license text found for $it" }
                }
        }
}

@Stable
fun listDependencies(context: Context): List<Pair<DependencyInfo, List<String>>> {
    return listDependencies { context.assets.open(it).readBytes().decodeToString() }
}
