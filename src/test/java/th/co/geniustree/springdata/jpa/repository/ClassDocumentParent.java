package th.co.geniustree.springdata.jpa.repository;

import lombok.Data;

@Data
public class ClassDocumentParent {

    private Long id;
    private String description;
    private String documentType;
    private String documentCategory;

    private ParentDTO parent;

    @Data
    public static class ParentDTO {
        private Long id;
        private String description;
    }
}
