<content:title>
    <fmt:message key="storybooks" /> (${fn:length(storybooks)})
</content:title>

<content:section cssId="storybookListPage">
    <div class="section row">
        <p>
            <fmt:message key="to.add.new.content.click.the.button.below" />
        </p>
        
        <c:forEach var="storyBook" items="${storyBooks}">
            <div class="col s12 m6 l4">
                <a name="${storyBook.id}"></a>
                <div class="storybook card-panel">
                    <h4><c:out value="${storyBook.title}" /></h4>
                    <p><fmt:message key="revision" />: ${storyBook.revisionNumber}</p>
                    
                    <div class="divider" style="margin: 1em 0;"></div>
                    
                    <a class="editLink" href="<spring:url value='/content/storybook/edit/${storyBook.id}' />"><i class="material-icons">edit</i><fmt:message key="edit" /></a>
                </div>
            </div>
        </c:forEach>
    </div>
    
    <div class="fixed-action-btn" style="bottom: 2em; right: 2em;">
        <a href="<spring:url value='/content/storybook/create' />" class="btn-floating btn-large grey tooltipped" data-position="left" data-delay="50" data-tooltip="<fmt:message key="add.storybook" />"><i class="material-icons">book</i></a>
    </div>
</content:section>