import { array, line, record } from './values.js'
export function projectView(value: unknown, verb: string): string {
    const row = record(value), name = line(row['name']), workspace = line(row['workspace'])
    const roots = row['lent'] == null ? [] : array(row['lent']), exclusions = array(row['exclusions'])
    const reach = `Its jobs reach ${workspace}, and these directories lent alongside it: ${roots.length ? roots.map(line).join(', ') : '(nothing)'}. A lent directory does not have to be inside the workspace, and it does not change where the project is.`
    const fence = `Inside it, these paths are excluded: ${exclusions.length ? exclusions.map(line).join(', ') : '(nothing)'}. The paths the server keeps for itself -- its own directory, its configuration, its agent definitions and its own console token -- are always covered, whether or not each is named above, and cannot be granted to any project.`
    const after: Record<string, string> = {
        define: "A job started in this project reads and writes there, as far as the running agent's own declared scopes allow. Defining a project again replaces the workspace, these exclusions and anything the project was lending; use project_workspace_set to move it and keep them, and project_lend to add a directory without rewriting anything.",
        workspace_set: "Runs already going pick this up at their next file request: the server re-reads the project's workspace on every one, so a job started before the move reads the new directory from here on, and nothing is answered out of the old one.",
        lend: 'Where the project IS did not change, and that is deliberate: its full name is MACHINE/PATH/NAME, so lending by moving the workspace would rename it. Runs already going pick this up at their next file request.',
        unlend: 'A directory the project was not lending is left out of that list rather than reported as an error, so if something you asked to take back is still there, it was spelled differently when it was lent.',
    }
    return `The project '${name}' is ${verb === 'workspace_set' ? 'now ' : ''}at ${workspace} on the server's disk.\n\n${reach}\n\n${fence}\n\n${after[verb]}`
}
export function moved(project: string, to: string): string {
    return `The project '${line(project)}' is now called '${line(to)}'.\n\nEverything it has remembered moved with it: its memories and its conversations reach the project through an id rather than through its name, so none of them was rewritten and none of them was lost. Its workspace on the server, the directories lent alongside it and the paths fenced off inside them are also unchanged — this moved the project, not its files.\n\nRecall and conversations in this project now answer to the new name. The old name holds nothing, and a project defined under it later would be a new, empty one.`
}
export function forgotten(project: string): string {
    return `The project '${line(project)}' no longer has a workspace, and nothing it was lending is lent any more, so its jobs reach no files on the server.\n\nIts memories are untouched: a project's archive and its workspace are separate things, and nothing was deleted from the archive here. Give it a workspace again with project_define.`
}
