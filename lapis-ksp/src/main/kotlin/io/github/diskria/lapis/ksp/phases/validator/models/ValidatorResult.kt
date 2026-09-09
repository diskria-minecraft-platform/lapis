package io.github.diskria.lapis.ksp.phases.validator.models

import io.github.diskria.lapis.ksp.phases.validator.models.patches.Patch
import io.github.diskria.lapis.ksp.phases.validator.models.schemas.Schema

class ValidatorResult(
    val schemas: List<Schema>,
    val patches: List<Patch>,
)
