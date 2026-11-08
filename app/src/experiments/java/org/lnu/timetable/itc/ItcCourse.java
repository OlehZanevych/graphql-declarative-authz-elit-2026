package org.lnu.timetable.itc;

import org.lnu.timetable.framework.annotation.GraphQLEntity;
import org.lnu.timetable.framework.annotation.PermissionRoot;

/** ITC 2019 course: a root of the hierarchy (a course coordinator's grant sits here). */
@GraphQLEntity(table = "itc_courses")
@PermissionRoot
public class ItcCourse {
    private Long id;
}
