package com.example.mealprep.provisions.api.mapper;

import com.example.mealprep.provisions.api.dto.SubstitutionRecordDto;
import com.example.mealprep.provisions.api.dto.SupplierProductDto;
import com.example.mealprep.provisions.domain.entity.SubstitutionRecord;
import com.example.mealprep.provisions.domain.entity.SupplierProduct;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * Mapper for the supplier-product aggregate. The {@code substitutionHistory} field is shape-mapped
 * element-by-element ({@link SubstitutionRecord} → {@link SubstitutionRecordDto}); the entity never
 * leaks beyond the persistence layer.
 */
@Mapper(componentModel = "spring")
public interface SupplierProductMapper {

  default SupplierProductDto toDto(SupplierProduct entity) {
    if (entity == null) {
      return null;
    }
    List<SubstitutionRecord> history = entity.getSubstitutionHistory();
    List<SubstitutionRecordDto> historyDtos =
        history == null
            ? List.of()
            : history.stream().map(SupplierProductMapper::toRecordDto).toList();
    return new SupplierProductDto(
        entity.getId(),
        entity.getProductId(),
        entity.getSupplier(),
        entity.getName(),
        entity.getPrice(),
        entity.getPricePerUnit(),
        entity.getUnit(),
        entity.getPackSizeG(),
        entity.getPackSizeUnit(),
        entity.getCategory(),
        entity.getClubcardPrice(),
        entity.getLastChecked(),
        historyDtos,
        entity.getIngredientMappingKey(),
        entity.getVersion());
  }

  static SubstitutionRecordDto toRecordDto(SubstitutionRecord r) {
    if (r == null) {
      return null;
    }
    return new SubstitutionRecordDto(
        r.date(), r.substitutedWithProductId(), r.accepted(), r.notes());
  }
}
