package com.example.mealprep.auth.api.mapper;

import com.example.mealprep.auth.api.dto.UserDto;
import com.example.mealprep.auth.domain.entity.User;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapping {@link User} to {@link UserDto}. The DTO's {@code userId} is sourced from the
 * entity's {@code id} field; everything else maps by name.
 */
@Mapper(componentModel = "spring")
public interface UserMapper {

  @Mapping(target = "userId", source = "id")
  UserDto toDto(User entity);

  List<UserDto> toDtos(List<User> entities);
}
