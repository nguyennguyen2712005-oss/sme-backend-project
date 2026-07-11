
package sme.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

@Entity
@Table(name = "knowledge_documents")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class KnowledgeDocument extends BaseSimpleEntity { // <-- SỬA Ở ĐÂY: Dùng BaseSimpleEntity

    @Column(nullable = false, length = 255)
    private String title;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "file_type", nullable = false, length = 50)
    private String fileType;

    @Column(name = "uploaded_by")
    private UUID uploadedByUserId;

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    // Biến ảo dùng để hiển thị số lượng phân đoạn (chunks) trên giao diện
    @Transient
    private Integer chunkCount;
}
