package com.example.mealprep.grocery.api.mapper;

import com.example.mealprep.grocery.api.dto.PriceObservationDto;
import com.example.mealprep.grocery.domain.entity.PriceObservation;
import org.mapstruct.Mapper;

/** {@link PriceObservation} → {@link PriceObservationDto} mapping. Per lld/grocery.md §Mappers. */
@Mapper(componentModel = "spring")
public interface PriceObservationMapper {

  PriceObservationDto toDto(PriceObservation entity);
}
