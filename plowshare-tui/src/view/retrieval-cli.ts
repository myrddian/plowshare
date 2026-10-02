import { UsageClient, usageCommand, runUsage, USAGE_HELP } from '../logic/usage.ts'
import { InformationClient, informationCommand, INFORMATION_HELP } from '../logic/information.ts'
import { fileURLToPath } from 'node:url'
import { isSea } from 'node:sea'
import { authenticateConfigured } from 'plowshare-client-node/session'
import { describeRetrieval, RETRIEVAL_HELP, retrievalCommand, retrieve } from '../logic/retrieval.ts'

/** No terminal renderer or agent selection is loaded by this command entry point. */
export async function retrievalCli(args: readonly string[], env: NodeJS.ProcessEnv,
        write: (text: string) => void, error: (text: string) => void): Promise<number> {
    if (args.includes('--help') || args.includes('-h')) { write(args[0]==='usage'?USAGE_HELP:args[0]==='information'?INFORMATION_HELP:RETRIEVAL_HELP.join('\n')); return 0 }
    let command
    let usage
    let information
    try { if(args[0]==='usage')usage=usageCommand(args,env['PLOWSHARE_PROJECT']?{project:env['PLOWSHARE_PROJECT']}:{});else if(args[0]==='information')information=informationCommand(args.map(word=>word.startsWith('{')?word:JSON.stringify(word)).join(' '),env['PLOWSHARE_PROJECT']);else command = retrievalCommand(args, env['PLOWSHARE_PROJECT']) }
    catch (failure) { error(failure instanceof Error ? failure.message : String(failure)); return 2 }
    try {
        const connection = await authenticateConfigured(env['PLOWSHARE_URL'] ?? 'http://127.0.0.1:8091', env, AbortSignal.timeout(30_000), () => undefined)
        try {
            if(usage){write(await runUsage(new UsageClient(connection),usage));return 0}
            if(information){write(JSON.stringify(await new InformationClient(connection,information.scope).call(information.operation,information.payload),null,2));return 0}
            if(!command)throw new Error('No command selected')
            const result = await retrieve(connection, command)
            write(command.json ? JSON.stringify(result) : describeRetrieval(result))
            if (!('hits' in result) && !result.complete) return 3
            if ('hits' in result && result.retrieval?.complete === false) return 3
            return 0
        } finally { await connection.close() }
    } catch (failure) { error(failure instanceof Error ? failure.message : String(failure)); return 1 }
}
// The embedded launcher chooses the mode; its executable URL is not this source entry.
if (!isSea() && process.argv[1] === fileURLToPath(import.meta.url)) {
    process.exitCode = await retrievalCli(process.argv.slice(2), process.env,
        text => process.stdout.write(text + '\n'), text => process.stderr.write(text + '\n'))
}
