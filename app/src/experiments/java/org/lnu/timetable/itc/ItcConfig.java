package org.lnu.timetable.itc;

import org.lnu.timetable.framework.annotation.GraphQLEntity;
import org.lnu.timetable.framework.annotation.PermissionParent;

@GraphQLEntity(table = "itc_configs")
@PermissionParent(value = ItcCourse.class, joinColumn = "course_id")
public class ItcConfig {
    private Long id;
    private Long courseId;
}
