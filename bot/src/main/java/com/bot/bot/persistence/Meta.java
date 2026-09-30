package com.bot.bot.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Table(name = "meta")
@Data
public class Meta {
    @Id
    @Column(name = "\"key\"")
    private String key;
    @Column(name = "\"value\"", columnDefinition = "TEXT")
    private String value;
}
