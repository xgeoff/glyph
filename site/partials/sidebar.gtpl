<%
def navItems = []
if (binding.hasVariable('navigation') && navigation instanceof Collection) {
    navItems = navigation
}
%>
<nav class="sidebar">
    <ul>
<% navItems.each { item -> %>
        ${partial('sidebarItem', [item: item])}
<% } %>
    </ul>
</nav>
