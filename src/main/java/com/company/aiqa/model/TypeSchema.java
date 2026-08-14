package com.company.aiqa.model;

import java.util.List;

/**
 * The shape of a request/response type, read from the project's own source.
 *
 * <p>Exists because telling the model only a type NAME ("request body:
 * AppointmentDto") leaves it to invent the fields, and it invents plausible
 * ones - customerName, customerPhone - that the real DTO doesn't have. The
 * create call then fails, the precondition never gets built, and the actual
 * test reports a 404 that looks like an application defect. Handing over the
 * real field names and types is what makes a generated payload accepted.
 *
 * <p>Enum constants are carried for the same reason: a status field is only
 * useful if the model knows the value must be COMPLETE rather than COMPLETED.
 */
public record TypeSchema(

        String simpleName,

        /** "class", "record" or "enum" - an enum carries values, the others carry fields. */
        String kind,

        /** Declared constants; empty unless this is an enum. */
        List<String> enumValues,

        List<FieldSchema> fields
) {

    /**
     * One property of a DTO or entity.
     *
     * @param required true when annotated @NotNull/@NotBlank/@NotEmpty - the
     *                 model needs to know which fields it cannot omit
     */
    public record FieldSchema(String name, String type, boolean required) {
    }
}
