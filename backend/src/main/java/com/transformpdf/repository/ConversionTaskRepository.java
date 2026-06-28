package com.transformpdf.repository;

import com.transformpdf.entity.ConversionTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ConversionTaskRepository extends JpaRepository<ConversionTask, Long> {

    List<ConversionTask> findAllByOrderByCreatedAtDesc();

    List<ConversionTask> findByStatusOrderByCreatedAtDesc(ConversionTask.TaskStatus status);

    List<ConversionTask> findByConversionTypeOrderByCreatedAtDesc(ConversionTask.ConversionType conversionType);
}
