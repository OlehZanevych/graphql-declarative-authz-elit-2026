package org.lnu.timetable.itc;

import org.lnu.timetable.framework.annotation.GraphQLEntity;
import org.lnu.timetable.framework.annotation.PermissionRoot;

@GraphQLEntity(table = "itc_rooms")
@PermissionRoot
public class ItcRoom {
    private Long id;
}
