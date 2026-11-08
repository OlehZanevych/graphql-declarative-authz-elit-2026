package org.lnu.timetable.itc;

import org.lnu.timetable.framework.annotation.GraphQLEntity;
import org.lnu.timetable.framework.annotation.PermissionJoinParent;
import org.lnu.timetable.framework.annotation.PermissionParent;

/**
 * ITC 2019 class. Its subpart and its optional parent class (a recursive edge, as Course has in
 * the LNU model) are foreign-key parents; the rooms it may use are a join-table parent that carries
 * no authority, as TimetableEntry's room edge does in the LNU model.
 */
@GraphQLEntity(table = "itc_classes")
@PermissionParent(value = ItcSubpart.class, joinColumn = "subpart_id")
@PermissionParent(value = ItcClass.class, joinColumn = "parent_class_id", nullable = true)
@PermissionJoinParent(value = ItcRoom.class, joinTable = "itc_class_rooms", selfColumn = "class_id",
    parentColumn = "room_id", authority = false)
public class ItcClass {
    private Long id;
    private Long subpartId;
    private Long parentClassId;
}
