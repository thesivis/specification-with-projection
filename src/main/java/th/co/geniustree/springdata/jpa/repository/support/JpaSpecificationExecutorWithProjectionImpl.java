package th.co.geniustree.springdata.jpa.repository.support;

import java.beans.PropertyDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.query.JpaEntityGraph;
import org.springframework.data.jpa.repository.query.QueryUtils;
import org.springframework.data.jpa.repository.support.JpaEntityInformation;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.data.mapping.PropertyPath;
import org.springframework.data.projection.ProjectionFactory;
import org.springframework.data.repository.query.MyResultProcessor;
import org.springframework.data.repository.query.ReturnTypeWarpper;
import org.springframework.data.repository.query.ReturnedType;
import org.springframework.data.repository.query.TupleConverter;
import org.springframework.data.repository.support.PageableExecutionUtils;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import th.co.geniustree.springdata.jpa.repository.JpaSpecificationExecutorWithProjection;

import javax.persistence.*;
import javax.persistence.criteria.*;
import javax.persistence.metamodel.Attribute;
import javax.persistence.metamodel.Bindable;
import javax.persistence.metamodel.ManagedType;
import javax.persistence.metamodel.PluralAttribute;
import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static javax.persistence.metamodel.Attribute.PersistentAttributeType.*;
import th.co.geniustree.springdata.jpa.annotation.FieldProperty;

/**
 * Created by pramoth on 9/29/2016 AD.
 */
public class JpaSpecificationExecutorWithProjectionImpl<T, ID extends Serializable> extends SimpleJpaRepository<T, ID> implements JpaSpecificationExecutorWithProjection<T, ID> {

    private static final Logger log = LoggerFactory.getLogger(JpaSpecificationExecutorWithProjectionImpl.class);
    private static final Map<Attribute.PersistentAttributeType, Class<? extends Annotation>> ASSOCIATION_TYPES;

    static {
        Map<Attribute.PersistentAttributeType, Class<? extends Annotation>> persistentAttributeTypes = new HashMap<Attribute.PersistentAttributeType, Class<? extends Annotation>>();
        persistentAttributeTypes.put(ONE_TO_ONE, OneToOne.class);
        persistentAttributeTypes.put(ONE_TO_MANY, null);
        persistentAttributeTypes.put(MANY_TO_ONE, ManyToOne.class);
        persistentAttributeTypes.put(MANY_TO_MANY, null);
        persistentAttributeTypes.put(ELEMENT_COLLECTION, null);

        ASSOCIATION_TYPES = Collections.unmodifiableMap(persistentAttributeTypes);
    }
    private final EntityManager entityManager;

    private final ProjectionFactory projectionFactory = new CustomSpelAwareProxyProjectionFactory();

    private final JpaEntityInformation entityInformation;

    public JpaSpecificationExecutorWithProjectionImpl(JpaEntityInformation entityInformation, EntityManager entityManager) {
        super(entityInformation, entityManager);
        this.entityManager = entityManager;
        this.entityInformation = entityInformation;
    }

    @Override
    public <R> Optional<R> findById(ID id, Class<R> projectionType) {
        try {
            boolean hasCollection = false;
            if (projectionType.isInterface()) {
                Method[] methods = projectionType.getDeclaredMethods();
                for (Method method : methods) {
                    if (Collection.class.isAssignableFrom(method.getReturnType())) {
                        hasCollection = true;
                        break;
                    }
                }
            }
            if (!hasCollection) {
                final ReturnTypeWarpper returnedType = ReturnTypeWarpper.of(projectionType, getDomainClass(), projectionFactory);

                CriteriaBuilder builder = this.entityManager.getCriteriaBuilder();
                CriteriaQuery<Tuple> q = builder.createQuery(Tuple.class);
                Root<T> root = q.from(getDomainClass());
                q.where(builder.equal(root.get(entityInformation.getIdAttribute()), id));

                if (returnedType.needsCustomConstruction()) {
                    configQuery(builder, q, root, returnedType, returnedType.getDomainType());
                } else {
                    throw new IllegalArgumentException("only except projection");
                }

                final TypedQuery<Tuple> query = this.applyRepositoryMethodMetadata(this.entityManager.createQuery(q));

                try {
                    final MyResultProcessor resultProcessor = new MyResultProcessor(projectionFactory, returnedType, entityManager);
                    final R singleResult = resultProcessor.processResult(query.getSingleResult(), new TupleConverter(returnedType));
                    return Optional.ofNullable(singleResult);
                } catch (NoResultException e) {
                    return Optional.empty();
                }
            } else {
                T t = this.entityManager.find(getDomainClass(), id);
                return Optional.ofNullable(projectionFactory.createProjection(projectionType, t));
            }
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }

    @Override
    public <R> Optional<R> findOne(Specification<T> spec, Class<R> projectionType) {
        final ReturnTypeWarpper returnedType = ReturnTypeWarpper.of(projectionType, getDomainClass(), projectionFactory);
        final TypedQuery<Tuple> query = getTupleQuery(spec, Sort.unsorted(), returnedType);
        try {
            final MyResultProcessor resultProcessor = new MyResultProcessor(projectionFactory, returnedType, entityManager);
            final R singleResult = resultProcessor.processResult(query.getSingleResult(), new TupleConverter(returnedType));
            return Optional.ofNullable(singleResult);
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }

    @Override
    public <R> Page<R> findAll(Specification<T> spec, Class<R> projectionType, Pageable pageable) {
        final ReturnTypeWarpper returnedType = ReturnTypeWarpper.of(projectionType, getDomainClass(), projectionFactory);
        final TypedQuery<Tuple> query = getTupleQuery(spec, pageable.getSort() != null && pageable.getSort().isSorted() ? pageable.getSort() : Sort.unsorted(), returnedType);
        final MyResultProcessor resultProcessor = new MyResultProcessor(projectionFactory, returnedType, entityManager);
        if (pageable.isPaged()) {
            query.setFirstResult((int) pageable.getOffset());
            query.setMaxResults(pageable.getPageSize());
        }
        final List<R> resultList = resultProcessor.processResult(query.getResultList(), new TupleConverter(returnedType));
        final Page<R> page = PageableExecutionUtils.getPage(resultList, pageable, () -> executeCountQuery(this.getCountQuery(spec, getDomainClass())));
        return pageable.isUnpaged() ? new PageImpl(resultList) : page;
    }

    static Long executeCountQuery(TypedQuery<Long> query) {
        Assert.notNull(query, "TypedQuery must not be null!");
        List<Long> totals = query.getResultList();
        Long total = 0L;

        Long element;
        for (Iterator var3 = totals.iterator(); var3.hasNext(); total = total + (element == null ? 0L : element)) {
            element = (Long) var3.next();
        }

        return total;
    }

    @Override
    public <R> Page<R> findAll(Specification<T> spec, Class<R> projectionType, String namedEntityGraph, org.springframework.data.jpa.repository.EntityGraph.EntityGraphType type, Pageable pageable) {
        return findAll(spec, projectionType, pageable);
    }

    @Override
    public <R> Page<R> findAll(Specification<T> spec, Class<R> projectionType, JpaEntityGraph dynamicEntityGraph, Pageable pageable) {
        return findAll(spec, projectionType, pageable);
    }

    protected TypedQuery<Tuple> getTupleQuery(@Nullable Specification spec, Sort sort, ReturnTypeWarpper returnedType) {
        if (!returnedType.needsCustomConstruction()) {
            return getQuery(spec, sort);
        }
        CriteriaBuilder builder = this.entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = builder.createQuery(Tuple.class);

        Root<T> root = query.from(getDomainClass());

        if (returnedType.needsCustomConstruction()) {
            configQuery(builder, query, root, returnedType, returnedType.getDomainType());
        } else {
            throw new IllegalArgumentException("only except projection");
        }

        if (spec != null) {
            Predicate predicate = spec.toPredicate(root, query, builder);

            if (predicate != null) {
                query.where(predicate);
            }
        }

        if (sort.isSorted()) {
            query.orderBy(QueryUtils.toOrders(sort, root, builder));
        }

        return this.applyRepositoryMethodMetadata(this.entityManager.createQuery(query));
    }

    public static void configQuery(CriteriaBuilder builder, CriteriaQuery<Tuple> query, From root, ReturnTypeWarpper returnedType, Class from) {
        Map<String, PropertyDescriptor> mapProps = null;
        boolean isInterface = returnedType.getReturnedType().isInterface();

        if (isInterface) {
            mapProps = returnedType.getInputPropertiesDescritors().stream().collect(Collectors.toMap(PropertyDescriptor::getName, Function.identity()));
        }

        List<Selection<?>> selections = new ArrayList<>();
        for (String property : returnedType.getInputProperties()) {
            if (isInterface) {
                FieldProperty field = null;
                PropertyDescriptor prop = mapProps.get(property);
                field = prop.getReadMethod().getAnnotation(FieldProperty.class);
                if (field != null) {
                    property = field.path();
                }
            }

            PropertyPath path = PropertyPath.from(property, from);
            Expression exp = (Expression) toExpressionRecursively(root, root, path, true).alias(property);
            selections.add(exp);
        }

        query.multiselect(selections);
    }

    private <S, U extends T> Root<U> applySpecificationToCriteria(@Nullable Specification<U> spec, Class<U> domainClass,
            CriteriaQuery<S> query, CriteriaBuilder builder) {

        Assert.notNull(domainClass, "Domain class must not be null!");
        Assert.notNull(query, "CriteriaQuery must not be null!");

        Root<U> root = query.from(domainClass);

        if (spec == null) {
            return root;
        }

        Predicate predicate = spec.toPredicate(root, query, builder);

        if (predicate != null) {
            query.where(predicate);
        }

        return root;
    }

    private <S> TypedQuery<S> applyRepositoryMethodMetadata(TypedQuery<S> query) {

        if (getRepositoryMethodMetadata() == null) {
            return query;
        }

        LockModeType type = getRepositoryMethodMetadata().getLockModeType();
        TypedQuery<S> toReturn = type == null ? query : query.setLockMode(type);
        applyQueryHints(toReturn);

        return toReturn;
    }

    private void applyQueryHints(Query query) {
        QueryHints queryHints = DefaultQueryHints.of(this.entityInformation, getRepositoryMethodMetadata());
        if (queryHints == null) {
            queryHints = QueryHints.NoHints.INSTANCE;
        }
        for (Map.Entry<String, Object> hint : queryHints.withFetchGraphs(this.entityManager)) {
            query.setHint(hint.getKey(), hint.getValue());
        }
    }

    static Expression<Object> toExpressionRecursively(Path<Object> path, PropertyPath property) {

        Path<Object> result = path.get(property.getSegment());
        return property.hasNext() ? toExpressionRecursively(result, property.next()) : result;
    }

    static <T> Expression<T> toExpressionRecursively(From<?, ?> root, From<?, ?> from, PropertyPath property, boolean isForSelection) {

        Bindable<?> propertyPathModel;
        Bindable<?> model = from.getModel();
        String segment = property.getSegment();

        if (model instanceof ManagedType) {

            /*
             *  Required to keep support for EclipseLink 2.4.x. TODO: Remove once we drop that (probably Dijkstra M1)
             *  See: https://bugs.eclipse.org/bugs/show_bug.cgi?id=413892
             */
            propertyPathModel = (Bindable<?>) ((ManagedType<?>) model).getAttribute(segment);
        } else {
            propertyPathModel = from.get(segment).getModel();
        }

        if (requiresJoin(propertyPathModel, model instanceof PluralAttribute, !property.hasNext(), isForSelection)
                && !isAlreadyFetched(from, segment)) {
            Join<?, ?> join = getOrCreateJoin(root, from, segment);
            return (Expression<T>) (property.hasNext() ? toExpressionRecursively(root, join, property.next(), isForSelection)
                    : join);
        } else {
            Path<Object> path = from.get(segment);
            return (Expression<T>) (property.hasNext() ? toExpressionRecursively(path, property.next()) : path);
        }
    }

    private static boolean requiresJoin(@Nullable Bindable<?> propertyPathModel, boolean isPluralAttribute,
            boolean isLeafProperty, boolean isForSelection) {

        if (propertyPathModel == null && isPluralAttribute) {
            return true;
        }

        if (!(propertyPathModel instanceof Attribute)) {
            return false;
        }

        Attribute<?, ?> attribute = (Attribute<?, ?>) propertyPathModel;

        if (!ASSOCIATION_TYPES.containsKey(attribute.getPersistentAttributeType())) {
            return false;
        }

        // if this path is part of the select list we need to generate an explicit outer join in order to prevent Hibernate
        // to use an inner join instead.
        // see https://hibernate.atlassian.net/browse/HHH-12999.
        if (isLeafProperty && !isForSelection && !attribute.isCollection()) {
            return false;
        }

        Class<? extends Annotation> associationAnnotation = ASSOCIATION_TYPES.get(attribute.getPersistentAttributeType());

        if (associationAnnotation == null) {
            return true;
        }

        Member member = attribute.getJavaMember();

        if (!(member instanceof AnnotatedElement)) {
            return true;
        }

        Annotation annotation = AnnotationUtils.getAnnotation((AnnotatedElement) member, associationAnnotation);
        return annotation == null ? true : (boolean) AnnotationUtils.getValue(annotation, "optional");
    }

    private static Join<?, ?> getOrCreateJoin(From<?, ?> root, From<?, ?> from, String attribute) {

        for (Join<?, ?> join : from.getJoins()) {

            boolean sameName = join.getAttribute().getName().equals(attribute);

            if (sameName && join.getJoinType().equals(JoinType.LEFT)) {
                return join;
            }
        }

        Join<?, ?> ret = from.join(attribute, JoinType.LEFT);
        if(!hasAlias(root, attribute)){        
          ret.alias(attribute);
        }
        
        return ret;
    }
    
    private static boolean hasAlias(From<?, ?> from, String alias){
      for (Join<?, ?> join : from.getJoins()) {
        if(alias.equals(join.getAlias()) || hasAlias(join, alias)){
          return true;
        }
      }
      
      return alias.equals(from.getAlias());
    }

    private static boolean isAlreadyFetched(From<?, ?> from, String attribute) {

        for (Fetch<?, ?> fetch : from.getFetches()) {

            boolean sameName = fetch.getAttribute().getName().equals(attribute);

            if (sameName && fetch.getJoinType().equals(JoinType.LEFT)) {
                return true;
            }
        }

        return false;
    }
}
