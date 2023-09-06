

import React, {useEffect, useState} from "react";
import authFetch from "../../util/auth/authFetch";
import {EuiFlexItem, EuiToolTip, EuiSpacer, EuiIconTip, EuiBadge, EuiFlexGroup, EuiInMemoryTable, EuiBasicTableColumn, EuiLoadingSpinner} from "@elastic/eui";
import '@elastic/eui/dist/eui_theme_light.css';
import hdate from 'human-date';
import {WorkspaceMetadata} from "../../types/Workspaces";
import moment from "moment";
import _ from "lodash";
import { BlobStatus, ExtractorStatus, IngestionTable, Status, extractorStatusColors } from "./types";

const blobStatusIcons = {
    complete: <EuiIconTip type="checkInCircleFilled" />,
    completeWithErrors: <EuiIconTip type="alert" />,
    inProgress: <EuiLoadingSpinner />
}

const statusToColor = (status: Status) => extractorStatusColors[status]

const getFailedStatuses = (statuses: ExtractorStatus[]) => 
    statuses.filter(status => status.statusUpdates.find(u => u.status === "Failure") !== undefined);

const getFailedBlobs = (blobs: BlobStatus[]) => {
    return  blobs.filter(wb => {                
        return getFailedStatuses(wb.extractorStatuses).length > 0;        
    });
}

const getBlobStatus = (statuses: ExtractorStatus[]) => {
    const failures = getFailedStatuses(statuses);
    const inProgress = statuses.filter(status => status.statusUpdates.find(u => !u.status || ["Failure", "Success"].includes(u.status)) === undefined)
    return failures.length > 0 ? blobStatusIcons.completeWithErrors : inProgress.length > 0 ? blobStatusIcons.inProgress : blobStatusIcons.complete
}

const extractorStatusTooltip = (status: ExtractorStatus) => {
    const statusUpdateStrings = status.statusUpdates.map(u => `${moment(u.eventTime).format("DD MMM HH:mm:ss")} ${u.status}`)
    return status.statusUpdates.length > 0 ? <>
        <b>All {status.extractorType} events</b> <br />
        <ul>
            {statusUpdateStrings.map(s => <li key={s}>{s}</li>)}
        </ul>
    </> : "No events so far"
}

const columns: Array<EuiBasicTableColumn<BlobStatus>> = [
    {
        field: 'extractorStatuses',
        name: '',
        render: (statuses: ExtractorStatus[]) => {
            return getBlobStatus(statuses)
        }
    },
    {
        field: 'paths',
        name: 'Filename(s)',
        sortable: true,
        truncateText: true,
        render: (paths: string[]) =>
            // throw away everything after last / to get the filename from a path
            paths.map(p => p.split("/").slice(-1)).join("\n")
    },
    {
        field: 'paths',
        name: 'Path(s)',
        render: (paths: string[]) => paths.join("\n")
    },
    {
        field: 'workspaceName',
        sortable: true,
        name: 'Workspace name'
    },
    {
        field: 'ingestStart',
        name: 'First event',
        sortable: true,
        render: (ingestStart: Date) => hdate.prettyPrint(ingestStart, {showTime: true})
    },
    {
        field: 'mostRecentEvent',
        name: 'Most recent event',
        sortable: true,
        render: (mostRecentEvent: Date) => hdate.prettyPrint(mostRecentEvent, {showTime: true})
    },
    {
        field: 'extractorStatuses',
        name: 'Extractors',
        render: (statuses: ExtractorStatus[]) => {
            return statuses.length > 0 ? (<ul>
                {statuses.map(status => {
                    const mostRecent = status.statusUpdates.length > 0 ? status.statusUpdates[status.statusUpdates.length - 1] : undefined
                    return <li key={status.extractorType}><EuiFlexGroup>
                        <EuiFlexItem>{status.extractorType.replace("Extractor", "")}</EuiFlexItem>
                        <EuiFlexItem grow={false}>
                            {mostRecent?.status ?
                                (<EuiToolTip content = {extractorStatusTooltip(status)}>
                                    <EuiBadge color={statusToColor(mostRecent.status)}>
                                        {mostRecent.status} ({moment(mostRecent.eventTime).format("HH:mm:ss")  })
                                    </EuiBadge>
                            </EuiToolTip>) : <>No updates</>
                            }
                        </EuiFlexItem>
                    </EuiFlexGroup></li>
            })}
            </ul>) : <></>

        },
        width: "300"
    },
];

const parseBlobStatus = (status: any): BlobStatus => {
    return {
        ...status,
        ingestStart: new Date(status.ingestStart),
        mostRecentEvent: new Date(status.mostRecentEvent),
        extractorStatuses: status.extractorStatuses.map((s: any) => ({
            extractorType: s.extractorType.replace("Extractor", ""),
            statusUpdates: _.sortBy(s.statusUpdates
                // discard empty status updates (does this make sense? Maybe we should tag them as 'unknown status' instead
                .filter((u: any) => u.eventTime !== undefined && u.status !== undefined)
                .map((u: any) => ({
                    ...u,
                    eventTime: new Date(u.eventTime)
            })), update => update.eventTime)
        }))
    }
}

export function IngestionEvents(
    {collectionId, ingestId, workspaces, breakdownByWorkspace, showErrorsOnly}: {
        collectionId: string,
        ingestId?: string,
        workspaces: WorkspaceMetadata[],
        breakdownByWorkspace: boolean,
        showErrorsOnly: boolean,
    }) {
    const [blobs, updateBlobs] = useState<BlobStatus[] | undefined>(undefined)
    const [tableData, setTableData] = useState<IngestionTable[]>([])

    const ingestIdSuffix = ingestId && ingestId !== "all" ? `/${ingestId}` : ""

    useEffect(() => {
        authFetch(`/api/ingestion-events/${collectionId}${ingestIdSuffix}`)
            .then(resp => resp.json())
            .then(json => {
                const blobStatuses: BlobStatus[] = json.map(parseBlobStatus)
                updateBlobs(blobStatuses)
        })
    }, [collectionId, ingestId, updateBlobs, ingestIdSuffix])

    const getWorkspaceBlobs = (allBlobs: BlobStatus[], workspaceName: string, errorsOnly: boolean | undefined) => {       
        const workspaceBlobs = allBlobs.filter(b => b.workspaceName === workspaceName);

        if (errorsOnly) return getFailedBlobs(workspaceBlobs);
        
        return workspaceBlobs;
    }

    useEffect(() => {
        if (blobs) {
            if (breakdownByWorkspace) {
                setTableData(workspaces
                    .map((w: WorkspaceMetadata) => ({
                        title: `Workspace: ${w.name}`,
                        blobs: getWorkspaceBlobs(blobs, w.name, showErrorsOnly)
                    })))
            } else {
                setTableData([
                    {
                        title: `${collectionId}${ingestIdSuffix}`,
                        blobs: showErrorsOnly ? getFailedBlobs(blobs) : blobs
                    }])
            }
        } else {
            setTableData([])
        }

    }, [breakdownByWorkspace, blobs, workspaces, ingestIdSuffix, collectionId, showErrorsOnly])

    return (
        <>
        {tableData.map((t: IngestionTable) =>
            <div key={t.title}>
                <EuiSpacer size={"m"}/>
                <h1>{t.title}</h1>
                <EuiInMemoryTable
                    tableCaption="ingestion events"
                    items={t.blobs}
                    itemId={(row: BlobStatus) => `${row.metadata.ingestId}-${row.metadata.blobId}`}
                    loading={t.blobs === undefined}
                    columns={columns}
                    sorting={true}
                />
            </div>
        )}
        </>
    )
}

