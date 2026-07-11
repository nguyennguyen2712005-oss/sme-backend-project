
package sme.backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore; // 1. Bổ sung import này
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "transfer_items")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class TransferItem extends BaseSimpleEntity {

    @JsonIgnore // 2. BỔ SUNG DÒNG NÀY ĐỂ NGẮT ĐỆ QUY
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transfer_id", nullable = false)
    private InternalTransfer transfer;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "received_qty")
    @Builder.Default
    private Integer receivedQty = 0;
}
