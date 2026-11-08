package org.lnu.timetable.itc;

import org.lnu.timetable.framework.annotation.GraphQLEntity;
import org.lnu.timetable.framework.annotation.PermissionParent;

@GraphQLEntity(table = "itc_subparts")
@PermissionParent(value = ItcConfig.class, joinColumn = "config_id")
public class ItcSubpart {
    private Long id;
    private Long configId;
}
